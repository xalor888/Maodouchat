package com.maodouchat.server.messaging.retention

import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.SignalDevices
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Retention policy for durable device mailboxes. All periods are measured from
 * acknowledgement time for acknowledged records and from server receipt time
 * for unacknowledged or device-retired records.
 */
data class MailboxRetentionPolicy(
    val acknowledgedRetentionMs: Long = 7L * DAY_MS,
    val unacknowledgedRetentionMs: Long = 30L * DAY_MS,
    val retiredDeviceRetentionMs: Long = DAY_MS,
) {
    init {
        require(acknowledgedRetentionMs >= 0) { "acknowledgedRetentionMs must be non-negative" }
        require(unacknowledgedRetentionMs >= 0) { "unacknowledgedRetentionMs must be non-negative" }
        require(retiredDeviceRetentionMs >= 0) { "retiredDeviceRetentionMs must be non-negative" }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

enum class MailboxRetentionReason {
    ACKNOWLEDGED,
    UNACKNOWLEDGED,
    DEVICE_RETIRED,
}

data class MailboxRetentionPurgeResult(
    val acknowledged: Int,
    val unacknowledged: Int,
    val deviceRetired: Int,
    val hasMore: Boolean,
) {
    val deleted: Int get() = acknowledged + unacknowledged + deviceRetired
}

/**
 * Bounded mailbox cleanup suitable for an externally leased scheduler. Each
 * batch locks and rechecks rows in one transaction, so duplicate execution by
 * an expired lease is harmless. Device retirement has no timestamp in the
 * current schema; callers should additionally invoke [purgeRetiredDevice] on
 * device removal for prompt cleanup.
 */
class MailboxRetentionService(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun purgeBatch(
        policy: MailboxRetentionPolicy = MailboxRetentionPolicy(),
        maxRows: Int = DEFAULT_BATCH_SIZE,
    ): MailboxRetentionPurgeResult {
        require(maxRows in 1..MAX_BATCH_SIZE) { "maxRows must be in 1..$MAX_BATCH_SIZE" }
        val now = clock()
        val acknowledgedCutoff = now - policy.acknowledgedRetentionMs
        val unacknowledgedCutoff = now - policy.unacknowledgedRetentionMs
        val retiredDeviceCutoff = now - policy.retiredDeviceRetentionMs
        return transaction {
            val candidates = MessagingV2Envelopes.selectAll()
                .where {
                    ((MessagingV2Envelopes.acknowledgedAt.isNotNull()) and
                        (MessagingV2Envelopes.acknowledgedAt lessEq acknowledgedCutoff)) or
                        ((MessagingV2Envelopes.acknowledgedAt.isNull()) and
                            (MessagingV2Envelopes.serverTimestamp lessEq unacknowledgedCutoff)) or
                        (MessagingV2Envelopes.serverTimestamp lessEq retiredDeviceCutoff)
                }
                .orderBy(MessagingV2Envelopes.sequence to SortOrder.ASC)
                .limit(maxRows + 1)
                .forUpdate()
                .toList()
            val selected = candidates.take(maxRows).mapNotNull { envelope ->
                reasonFor(envelope, policy, now)?.let { reason -> envelope to reason }
            }
            var acknowledged = 0
            var unacknowledged = 0
            var deviceRetired = 0
            selected.forEach { (envelope, reason) ->
                if (MessagingV2Envelopes.deleteWhere { MessagingV2Envelopes.id eq envelope[MessagingV2Envelopes.id] } == 0) {
                    return@forEach
                }
                when (reason) {
                    MailboxRetentionReason.ACKNOWLEDGED -> acknowledged++
                    MailboxRetentionReason.UNACKNOWLEDGED -> unacknowledged++
                    MailboxRetentionReason.DEVICE_RETIRED -> deviceRetired++
                }
            }
            MailboxRetentionPurgeResult(
                acknowledged = acknowledged,
                unacknowledged = unacknowledged,
                deviceRetired = deviceRetired,
                hasMore = candidates.size > maxRows,
            )
        }
    }

    /** Deletes every queued envelope for a device that has just been retired. */
    fun purgeRetiredDevice(userId: String, deviceId: Int): Int = transaction {
        MessagingV2Envelopes.deleteWhere {
            (MessagingV2Envelopes.recipientUserId eq userId) and
                (MessagingV2Envelopes.recipientDeviceId eq deviceId)
        }
    }

    private fun reasonFor(
        envelope: org.jetbrains.exposed.sql.ResultRow,
        policy: MailboxRetentionPolicy,
        now: Long,
    ): MailboxRetentionReason? {
        val acknowledgedAt = envelope[MessagingV2Envelopes.acknowledgedAt]
        if (acknowledgedAt != null && acknowledgedAt <= now - policy.acknowledgedRetentionMs) {
            return MailboxRetentionReason.ACKNOWLEDGED
        }
        val userId = envelope[MessagingV2Envelopes.recipientUserId]
        val deviceId = envelope[MessagingV2Envelopes.recipientDeviceId]
        val activeDevice = SignalDevices.selectAll().where {
            (SignalDevices.userId eq userId) and (SignalDevices.deviceId eq deviceId)
        }.firstOrNull() != null
        if (!activeDevice && envelope[MessagingV2Envelopes.serverTimestamp] <= now - policy.retiredDeviceRetentionMs) {
            return MailboxRetentionReason.DEVICE_RETIRED
        }
        if (acknowledgedAt == null && envelope[MessagingV2Envelopes.serverTimestamp] <= now - policy.unacknowledgedRetentionMs) {
            return MailboxRetentionReason.UNACKNOWLEDGED
        }
        return null
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 500
        const val MAX_BATCH_SIZE = 2_000
    }
}
