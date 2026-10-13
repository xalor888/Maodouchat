package com.maodouchat.messaging.v2

internal data class MessagingV2ArrivalDecision(
    val unreadDelta: Int,
    val shouldAttemptNotification: Boolean,
    val shouldSendDeliveryReceipt: Boolean,
)
