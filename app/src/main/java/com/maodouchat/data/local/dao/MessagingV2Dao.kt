package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Transaction

@Dao
interface MessagingV2Dao : MessagingV2TombstoneDao, MessagingV2InboxDao, MessagingV2OutboxDao, MessagingV2ReceiptDao {
    @Transaction
    suspend fun clearConversationState(
        ownerUserId: String,
        conversationId: String,
        serverParticipantStateDeleted: Boolean,
        now: Long,
    ) {
        deleteConversationOutbox(ownerUserId, conversationId)
        deleteConversationReceipts(ownerUserId, conversationId)
        if (serverParticipantStateDeleted) {
            deleteConversationInbox(ownerUserId, conversationId)
        } else {
            deleteConversationDeadLetters(ownerUserId, conversationId)
            discardConversationInboxForAck(ownerUserId, conversationId, now)
        }
    }
}
