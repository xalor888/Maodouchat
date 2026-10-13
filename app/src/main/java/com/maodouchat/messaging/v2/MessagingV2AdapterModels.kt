package com.maodouchat.messaging.v2

data class MessagingV2DeviceTarget(val userId: String, val deviceId: Int)

data class MessagingV2ConversationSnapshot(
    val conversationId: String,
    val ownerUserId: String,
    val isGroup: Boolean,
    val memberRevision: Long,
    val participantUserIds: List<String>,
    /** Every confirmed destination device except the current sender device. */
    val targets: Set<MessagingV2DeviceTarget>,
)
