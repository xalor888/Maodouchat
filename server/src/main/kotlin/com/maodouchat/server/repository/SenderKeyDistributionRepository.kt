package com.maodouchat.server.repository

import com.maodouchat.server.db.SenderKeyDistributions
import com.maodouchat.server.model.SenderKeyDistributionStatusResponse
import com.maodouchat.server.model.SenderKeyDistributionTargetRequest
import com.maodouchat.server.model.SenderKeyDistributionTargetResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

class SenderKeyDistributionRepository {

    fun record(
        chatId: String,
        epoch: Long,
        senderId: String,
        messageId: String?,
        targets: List<SenderKeyDistributionTargetRequest>
    ) {
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        transaction {
            targets
                .filter { it.userId.isNotBlank() && it.deviceId in 1..255 }
                .distinctBy { it.userId to it.deviceId }
                .forEach { target ->
                    val id = rowId(chatId, epoch, senderId, target.userId, target.deviceId)
                    val normalizedStatus = normalizeStatus(target.status)
                    // 9.307：此前 PG 分支用 Exposed upsert(onUpdate = createdAt to createdAt)——
                    // Column 作为更新值被渲染成 Kotlin 对象全限定名，PG 报
                    // "improper qualified name" 直接 500（H2 不触发 → 本地测试全绿但生产全挂），
                    // 导致群 SenderKey 分发上报永久失败、群消息发送链路卡死。
                    // 改为全库通用的 select→update/insert：冲突时保留原 createdAt（purge 依赖），
                    // 并发 PK 撞车时 insert 失败降级为 update，语义与 upsert 一致。
                    val existing = SenderKeyDistributions.selectAll()
                        .where { SenderKeyDistributions.id eq id }
                        .firstOrNull()
                    if (existing == null) {
                        val inserted = runCatching {
                            SenderKeyDistributions.insert {
                                it[SenderKeyDistributions.id] = id
                                it[SenderKeyDistributions.chatId] = chatId
                                it[SenderKeyDistributions.epoch] = epoch
                                it[SenderKeyDistributions.senderId] = senderId
                                it[SenderKeyDistributions.recipientUserId] = target.userId
                                it[SenderKeyDistributions.recipientDeviceId] = target.deviceId
                                it[SenderKeyDistributions.messageId] = messageId
                                it[SenderKeyDistributions.status] = normalizedStatus
                                it[SenderKeyDistributions.error] = target.error?.take(200)
                                it[SenderKeyDistributions.createdAt] = now
                                it[SenderKeyDistributions.updatedAt] = now
                            }
                        }.isSuccess
                        // 并发 report 撞 PK：降级为 update（保留原 createdAt）
                        if (!inserted) {
                            SenderKeyDistributions.update({ SenderKeyDistributions.id eq id }) {
                                it[SenderKeyDistributions.chatId] = chatId
                                it[SenderKeyDistributions.epoch] = epoch
                                it[SenderKeyDistributions.senderId] = senderId
                                it[SenderKeyDistributions.recipientUserId] = target.userId
                                it[SenderKeyDistributions.recipientDeviceId] = target.deviceId
                                it[SenderKeyDistributions.messageId] = messageId
                                it[SenderKeyDistributions.status] = normalizedStatus
                                it[SenderKeyDistributions.error] = target.error?.take(200)
                                it[SenderKeyDistributions.updatedAt] = now
                            }
                        }
                    } else {
                        SenderKeyDistributions.update({ SenderKeyDistributions.id eq id }) {
                            it[SenderKeyDistributions.chatId] = chatId
                            it[SenderKeyDistributions.epoch] = epoch
                            it[SenderKeyDistributions.senderId] = senderId
                            it[SenderKeyDistributions.recipientUserId] = target.userId
                            it[SenderKeyDistributions.recipientDeviceId] = target.deviceId
                            it[SenderKeyDistributions.messageId] = messageId
                            it[SenderKeyDistributions.status] = normalizedStatus
                            it[SenderKeyDistributions.error] = target.error?.take(200)
                            it[SenderKeyDistributions.updatedAt] = now
                        }
                    }
                }
        }
    }

    fun getStatus(
        chatId: String,
        senderId: String,
        epoch: Long? = null,
        expectedTargets: Set<Pair<String, Int>>? = null
    ): SenderKeyDistributionStatusResponse {
        return transaction {
            val effectiveEpoch = epoch ?: latestEpoch(chatId, senderId)
            if (effectiveEpoch == null) {
                return@transaction SenderKeyDistributionStatusResponse(chatId, 0, 0, 0, 0, 0, emptyList())
            }
            val recordedTargets = SenderKeyDistributions.selectAll()
                .where {
                    (SenderKeyDistributions.chatId eq chatId) and
                        (SenderKeyDistributions.epoch eq effectiveEpoch) and
                        (SenderKeyDistributions.senderId eq senderId)
                }
                .orderBy(SenderKeyDistributions.recipientUserId to SortOrder.ASC, SenderKeyDistributions.recipientDeviceId to SortOrder.ASC)
                .map {
                    SenderKeyDistributionTargetResponse(
                        userId = it[SenderKeyDistributions.recipientUserId],
                        deviceId = it[SenderKeyDistributions.recipientDeviceId],
                        status = it[SenderKeyDistributions.status],
                        error = it[SenderKeyDistributions.error],
                        updatedAt = it[SenderKeyDistributions.updatedAt]
                    )
                }
                // Legacy row IDs did not include senderId. Keep only the newest row per target
                // while old records age out naturally.
                .groupBy { it.userId to it.deviceId }
                .map { (_, rows) -> rows.maxBy(SenderKeyDistributionTargetResponse::updatedAt) }

            val recordedByTarget = recordedTargets.associateBy { it.userId to it.deviceId }
            // A GET coverage check supplies the authoritative current device set, so removed
            // members/devices must disappear even if historical rows remain. POST responses omit
            // this set and return the rows just recorded.
            val targetKeys = expectedTargets ?: recordedByTarget.keys
            val targets = targetKeys
                .map { target ->
                    recordedByTarget[target] ?: SenderKeyDistributionTargetResponse(
                        userId = target.first,
                        deviceId = target.second,
                        status = STATUS_PENDING,
                        error = "device_not_covered",
                        updatedAt = 0L
                    )
                }
                .sortedWith(compareBy(SenderKeyDistributionTargetResponse::userId, SenderKeyDistributionTargetResponse::deviceId))
            SenderKeyDistributionStatusResponse(
                chatId = chatId,
                epoch = effectiveEpoch,
                total = targets.size,
                sent = targets.count { it.status == STATUS_SENT },
                failed = targets.count { it.status == STATUS_FAILED },
                pending = targets.count { it.status == STATUS_PENDING },
                targets = targets
            )
        }
    }

    private fun latestEpoch(chatId: String, senderId: String): Long? =
        SenderKeyDistributions.select(SenderKeyDistributions.epoch)
            .where { (SenderKeyDistributions.chatId eq chatId) and (SenderKeyDistributions.senderId eq senderId) }
            .orderBy(SenderKeyDistributions.epoch to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SenderKeyDistributions.epoch)

    private fun rowId(chatId: String, epoch: Long, senderId: String, userId: String, deviceId: Int): String =
        "skd_${UUID.nameUUIDFromBytes("$chatId:$epoch:$senderId:$userId:$deviceId".toByteArray())}"

    /**
     * 清理超过保留期的 sender key 分发记录（默认 365 天）。
     * 覆盖检查只关心最新 epoch，历史 epoch 行超期后可安全删除，防止无限累积。
     * 由 Routing.kt 的周期清理循环调用。
     */
    fun purgeOldRecords(retentionDays: Int = 365): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            SenderKeyDistributions.deleteWhere { SenderKeyDistributions.createdAt less cutoff }
        }
    }

    private fun normalizeStatus(status: String): String = when (status.uppercase()) {
        STATUS_SENT -> STATUS_SENT
        STATUS_FAILED -> STATUS_FAILED
        STATUS_PENDING -> STATUS_PENDING
        // 未知状态不得抬成 SENT，否则覆盖检查会假完整
        else -> STATUS_PENDING
    }

    private companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_PENDING = "PENDING"
    }
}
