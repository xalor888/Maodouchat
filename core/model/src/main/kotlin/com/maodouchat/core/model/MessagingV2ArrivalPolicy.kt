package com.maodouchat.core.model

/** Pure policy for effects caused by one decrypted inbox message. */
object MessagingV2ArrivalPolicy {
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
