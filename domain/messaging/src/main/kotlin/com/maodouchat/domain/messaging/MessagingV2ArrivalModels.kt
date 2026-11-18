package com.maodouchat.domain.messaging

data class MessagingV2ArrivalDecision(
    val unreadDelta: Int,
    val shouldAttemptNotification: Boolean,
    val shouldSendDeliveryReceipt: Boolean,
)
