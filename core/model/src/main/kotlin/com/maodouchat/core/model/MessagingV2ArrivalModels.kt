package com.maodouchat.core.model

data class MessagingV2ArrivalDecision(
    val unreadDelta: Int,
    val shouldAttemptNotification: Boolean,
    val shouldSendDeliveryReceipt: Boolean,
)
