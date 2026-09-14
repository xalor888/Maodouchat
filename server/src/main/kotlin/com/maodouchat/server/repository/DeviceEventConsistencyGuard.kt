package com.maodouchat.server.repository

import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.DeviceEventSequences
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * 设备事件序列守卫：按 (userId, deviceId, eventType) 维护 lastAppliedSeq，
 * 拒绝 STALE（seq 落后）/ DUPLICATE（重复投递）事件；seq 跳号记为 OUT_OF_ORDER。
 * 单进程内按 key 加条纹锁串行化读-改-写；多实例部署需换 DB 行级锁（与 GlobalRateLimiter 同约束）。
 *
 * M2：从 plugins/AdminEnhanceRouting.kt 迁到 repository/（它整体是 SQL 逻辑）。
 * 注意：迁入时实测**全仓没有任何调用方**（`grep -rn "applyEvent" server/src` 只命中定义本身），
 * 保留而非删除是因为它看起来是预留的设备事件加固扩展点。
 */
object DeviceEventConsistencyGuard {
    private val stripes = Array(256) { Any() }

    enum class Status { APPLIED, STALE, DUPLICATE, OUT_OF_ORDER }

    data class ApplyOutcome(val status: Status, val lastAppliedSeq: Long)

    private fun stripe(userId: String, deviceId: Int, eventType: String): Any {
        val hash = (userId.hashCode() * 31 + deviceId) * 31 + eventType.hashCode()
        return stripes[Math.floorMod(hash, stripes.size)]
    }

    fun applyEvent(
        userId: String,
        deviceId: Int,
        eventType: String,
        seq: Long,
        referenceId: String? = null,
        now: Long = System.currentTimeMillis()
    ): ApplyOutcome {
        val lock = stripe(userId, deviceId, eventType)
        synchronized(lock) {
            return transaction {
                val existing = DeviceEventSequences.selectAll().where {
                    (DeviceEventSequences.userId eq userId) and
                        (DeviceEventSequences.deviceId eq deviceId) and
                        (DeviceEventSequences.eventType eq eventType)
                }.firstOrNull()

                if (existing == null) {
                    DeviceEventSequences.insert {
                        it[DeviceEventSequences.userId] = userId
                        it[DeviceEventSequences.deviceId] = deviceId
                        it[DeviceEventSequences.eventType] = eventType
                        it[DeviceEventSequences.lastAppliedSeq] = seq
                        it[DeviceEventSequences.lastEventAt] = now
                    }
                    return@transaction ApplyOutcome(Status.APPLIED, seq)
                }

                val lastApplied = existing[DeviceEventSequences.lastAppliedSeq]
                when {
                    seq < lastApplied -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "STALE", referenceId, now, "last=$lastApplied")
                        ApplyOutcome(Status.STALE, lastApplied)
                    }
                    seq == lastApplied -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "DUPLICATE", referenceId, now, "already=$lastApplied")
                        ApplyOutcome(Status.DUPLICATE, lastApplied)
                    }
                    seq > lastApplied + 1 -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "OUT_OF_ORDER", referenceId, now, "expected=${lastApplied + 1}")
                        ApplyOutcome(Status.OUT_OF_ORDER, lastApplied)
                    }
                    else -> {
                        DeviceEventSequences.update({
                            (DeviceEventSequences.userId eq userId) and
                                (DeviceEventSequences.deviceId eq deviceId) and
                                (DeviceEventSequences.eventType eq eventType)
                        }) {
                            it[DeviceEventSequences.lastAppliedSeq] = seq
                            it[DeviceEventSequences.lastEventAt] = now
                        }
                        ApplyOutcome(Status.APPLIED, seq)
                    }
                }
            }
        }
    }

    /** 异常汇总：按事件类型 + 状态统计（供仪表盘）。 */
    fun anomalySummary(userId: String? = null): Map<String, Long> = transaction {
        val q = DeviceEventConsistencyLog.selectAll()
        val rows = if (userId != null) q.andWhere { DeviceEventConsistencyLog.userId eq userId } else q
        rows.groupBy { it[DeviceEventConsistencyLog.status] }.mapValues { (_, v) -> v.size.toLong() }
    }

    private fun recordAnomaly(
        userId: String,
        deviceId: Int,
        eventType: String,
        seq: Long,
        status: String,
        referenceId: String?,
        now: Long,
        detail: String
    ) {
        val id = "${userId.take(32)}|$deviceId|${eventType.take(16)}|$status"
        val existing = DeviceEventConsistencyLog.selectAll().where { DeviceEventConsistencyLog.id eq id }.firstOrNull()
        if (existing == null) {
            DeviceEventConsistencyLog.insert {
                it[DeviceEventConsistencyLog.id] = id
                it[DeviceEventConsistencyLog.userId] = userId
                it[DeviceEventConsistencyLog.deviceId] = deviceId
                it[DeviceEventConsistencyLog.eventType] = eventType
                it[DeviceEventConsistencyLog.seq] = seq
                it[DeviceEventConsistencyLog.status] = status
                it[DeviceEventConsistencyLog.referenceId] = referenceId
                it[DeviceEventConsistencyLog.firstSeenAt] = now
                it[DeviceEventConsistencyLog.lastSeenAt] = now
                it[DeviceEventConsistencyLog.detail] = detail.take(300)
            }
        } else {
            DeviceEventConsistencyLog.update({ DeviceEventConsistencyLog.id eq id }) {
                it[DeviceEventConsistencyLog.seq] = seq
                it[DeviceEventConsistencyLog.referenceId] = referenceId
                it[DeviceEventConsistencyLog.lastSeenAt] = now
                it[DeviceEventConsistencyLog.detail] = detail.take(300)
            }
        }
    }
}
