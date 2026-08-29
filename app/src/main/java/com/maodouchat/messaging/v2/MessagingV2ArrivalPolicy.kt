package com.maodouchat.messaging.v2

import com.maodouchat.data.model.MessageType

internal data class MessagingV2ArrivalDecision(
    val unreadDelta: Int,
    val shouldAttemptNotification: Boolean,
    val shouldSendDeliveryReceipt: Boolean,
)

/** Pure policy for effects caused by one decrypted inbox message. */
internal object MessagingV2ArrivalPolicy {
    fun evaluate(
        isNew: Boolean,
        ownerUserId: String,
        senderUserId: String,
        conversationId: String,
        messageType: MessageType,
        envelopeKind: String,
        isConversationOccupied: Boolean,
        appInForeground: Boolean,
        activeChatId: String?,
        openChatDetailId: String?,
    ): MessagingV2ArrivalDecision {
        val isVisibleIncoming = isNew &&
            ownerUserId.isNotBlank() &&
            senderUserId != ownerUserId &&
            messageType != MessageType.SK_DIST
        return MessagingV2ArrivalDecision(
            unreadDelta = if (isVisibleIncoming && !isConversationOccupied) 1 else 0,
            shouldAttemptNotification = isVisibleIncoming &&
                !appInForeground &&
                activeChatId != conversationId &&
                openChatDetailId != conversationId,
            shouldSendDeliveryReceipt = isVisibleIncoming && envelopeKind != "SERVICE",
        )
    }
}
