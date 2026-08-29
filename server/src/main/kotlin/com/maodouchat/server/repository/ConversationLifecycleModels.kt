package com.maodouchat.server.repository

enum class LeaveConversationResult {
    LEFT,
    NOT_PARTICIPANT,
    OWNER_TRANSFER_REQUIRED,
}

data class LeaveConversationOutcome(
    val result: LeaveConversationResult,
    val wasGroup: Boolean = false,
    val recipientsBefore: List<String> = emptyList(),
    val memberRevisionAfter: Long? = null,
    val conversationDeleted: Boolean = false,
    val deletedAttachmentIds: List<String> = emptyList(),
    val deletedGroupAvatarUrl: String? = null,
)
