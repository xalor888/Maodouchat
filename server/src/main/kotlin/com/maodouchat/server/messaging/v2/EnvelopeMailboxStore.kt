package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** B06：设备邮箱子域。未 ACK 信封拉取与幂等 ACK。 */
class EnvelopeMailboxStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun pending(userId: String, deviceId: Int, limit: Int): PendingInboxV2Response = transaction {
        val rows = (MessagingV2Envelopes innerJoin MessagingV2Messages)
            .selectAll()
            .where {
                (MessagingV2Envelopes.recipientUserId eq userId) and
                    (MessagingV2Envelopes.recipientDeviceId eq deviceId) and
                    MessagingV2Envelopes.acknowledgedAt.isNull()
            }
            .orderBy(MessagingV2Envelopes.sequence to SortOrder.ASC)
            .limit(limit + 1)
            .toList()
        PendingInboxV2Response(
            envelopes = rows.take(limit).map { row ->
                PendingEnvelopeV2(
                    envelopeId = row[MessagingV2Envelopes.id],
                    sequence = row[MessagingV2Envelopes.sequence],
                    messageId = row[MessagingV2Messages.id],
                    conversationId = row[MessagingV2Messages.conversationId],
                    senderUserId = row[MessagingV2Messages.senderUserId],
                    senderDeviceId = row[MessagingV2Messages.senderDeviceId],
                    kind = row[MessagingV2Messages.kind],
                    groupRevision = row[MessagingV2Messages.groupRevision],
                    clientTimestamp = row[MessagingV2Messages.clientTimestamp],
                    serverTimestamp = row[MessagingV2Messages.serverTimestamp],
                    ciphertextType = row[MessagingV2Envelopes.ciphertextType],
                    ciphertext = row[MessagingV2Envelopes.ciphertext],
                )
            },
            hasMore = rows.size > limit,
        )
    }

    fun acknowledge(userId: String, deviceId: Int, envelopeIds: Set<String>): Int {
        if (envelopeIds.isEmpty()) return 0
        val now = clock()
        return transaction {
            val ownedIds = MessagingV2Envelopes
                .select(MessagingV2Envelopes.id)
                .where {
                    (MessagingV2Envelopes.id inList envelopeIds) and
                        (MessagingV2Envelopes.recipientUserId eq userId) and
                        (MessagingV2Envelopes.recipientDeviceId eq deviceId)
                }
                .mapTo(linkedSetOf()) { it[MessagingV2Envelopes.id] }
            if (ownedIds.isEmpty()) return@transaction 0
            MessagingV2Envelopes.update({
                (MessagingV2Envelopes.id inList ownedIds) and
                    (MessagingV2Envelopes.recipientUserId eq userId) and
                    (MessagingV2Envelopes.recipientDeviceId eq deviceId) and
                    MessagingV2Envelopes.acknowledgedAt.isNull()
            }) {
                it[acknowledgedAt] = now
            }
            ownedIds.size
        }
    }

}
