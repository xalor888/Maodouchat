package com.maodouchat.server.repository

import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.model.SenderKeyDistributionStatusResponse
import com.maodouchat.server.model.SenderKeyDistributionTargetResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Read-only Sender Key coverage projection.
 *
 * Coverage is derived exclusively from immutable v2 Sender Key messages and
 * their committed per-device envelopes. There is intentionally no client
 * report/write API and no mutable telemetry table that can claim delivery.
 */
class SenderKeyDistributionRepository {

    fun getStatus(
        chatId: String,
        senderId: String,
        epoch: Long? = null,
        expectedTargets: Set<Pair<String, Int>>? = null,
    ): SenderKeyDistributionStatusResponse = transaction {
        val effectiveEpoch = epoch ?: latestDurableEpoch(chatId, senderId)
        if (effectiveEpoch == null) {
            return@transaction SenderKeyDistributionStatusResponse(chatId, 0, 0, 0, 0, 0, emptyList())
        }

        val durableTargets = durableSenderKeyTargets(chatId, senderId, effectiveEpoch)
        val targetKeys = expectedTargets ?: durableTargets
        val targets = targetKeys
            .map { target ->
                if (target in durableTargets) {
                    SenderKeyDistributionTargetResponse(
                        userId = target.first,
                        deviceId = target.second,
                        status = STATUS_SENT,
                        error = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    SenderKeyDistributionTargetResponse(
                        userId = target.first,
                        deviceId = target.second,
                        status = STATUS_PENDING,
                        error = "device_not_covered",
                        updatedAt = 0L,
                    )
                }
            }
            .sortedWith(compareBy(SenderKeyDistributionTargetResponse::userId, SenderKeyDistributionTargetResponse::deviceId))

        SenderKeyDistributionStatusResponse(
            chatId = chatId,
            epoch = effectiveEpoch,
            total = targets.size,
            sent = targets.count { it.status == STATUS_SENT },
            failed = targets.count { it.status == STATUS_FAILED },
            pending = targets.count { it.status == STATUS_PENDING },
            targets = targets,
        )
    }

    private fun latestDurableEpoch(chatId: String, senderId: String): Long? =
        MessagingV2Messages
            .selectAll()
            .where {
                (MessagingV2Messages.conversationId eq chatId) and
                    (MessagingV2Messages.senderUserId eq senderId) and
                    (MessagingV2Messages.kind eq KIND_SENDER_KEY)
            }
            .orderBy(MessagingV2Messages.serverTimestamp to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(MessagingV2Messages.groupRevision)

    private fun durableSenderKeyTargets(
        chatId: String,
        senderId: String,
        epoch: Long,
    ): Set<Pair<String, Int>> {
        val messageId = MessagingV2Messages
            .selectAll()
            .where {
                (MessagingV2Messages.conversationId eq chatId) and
                    (MessagingV2Messages.senderUserId eq senderId) and
                    (MessagingV2Messages.kind eq KIND_SENDER_KEY) and
                    (MessagingV2Messages.groupRevision eq epoch)
            }
            .orderBy(MessagingV2Messages.serverTimestamp to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(MessagingV2Messages.id)
            ?: return emptySet()
        return MessagingV2Envelopes
            .selectAll()
            .where { MessagingV2Envelopes.messageId eq messageId }
            .mapTo(linkedSetOf()) {
                it[MessagingV2Envelopes.recipientUserId] to it[MessagingV2Envelopes.recipientDeviceId]
            }
    }

    private companion object {
        const val KIND_SENDER_KEY = "SENDER_KEY"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_PENDING = "PENDING"
    }
}
