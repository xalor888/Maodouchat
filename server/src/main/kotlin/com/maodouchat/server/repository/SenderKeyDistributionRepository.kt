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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
                    // upsert：并发 report 撞 PK 不得 abort 整批 targets。
                    // 8.39：onUpdate 仅列 createdAt → 引用原行（冲突时保持不变）——
                    // 反复 report 同一 target 若刷新 createdAt，purgeOldRecords 按 createdAt
                    // 清理会因无限续期而失效，表随活跃目标无限增长；其余列自动用 insert 值更新。
                    SenderKeyDistributions.upsert(
                        SenderKeyDistributions.id,
                        onUpdate = listOf(SenderKeyDistributions.createdAt to SenderKeyDistributions.createdAt)
                    ) {
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
