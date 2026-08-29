package com.maodouchat.server.repository

enum class GroupInviteAcceptResult {
    ACCEPTED,
    NOT_FOUND,
    NOT_PENDING,
    NOT_INVITEE,
    CHAT_NOT_FOUND,
    NOT_GROUP,
    CHANNEL_NOT_SUPPORTED,
    MEMBER_LIMIT_EXCEEDED,
    BLOCKED,
    ALREADY_MEMBER,
    USER_DEACTIVATED,
}

data class GroupInviteAcceptOutcome(
    val result: GroupInviteAcceptResult,
    val chatId: String? = null,
    val memberRevisionAfter: Long? = null,
    val recipientsAfter: List<String> = emptyList(),
)

data class GroupInviteResult(
    val result: GroupMemberMutationResult,
    val invitedUserIds: List<String> = emptyList(),
    val skippedMemberIds: List<String> = emptyList(),
    val missingUserId: String? = null,
)

data class GroupInviteState(
    val token: String,
    val expiresAt: Long,
    val maxUses: Int,
    val usedCount: Int,
    val changed: Boolean,
) {
    val remainingUses: Int get() = (maxUses - usedCount).coerceAtLeast(0)
}

data class GroupInviteMutationResult(
    val result: GroupMemberMutationResult,
    val invite: GroupInviteState? = null,
)

data class JoinGroupInviteResult(
    val chatId: String,
    val newlyJoined: Boolean,
    val limitExceeded: Boolean = false,
    val blocked: Boolean = false,
    val channelRejected: Boolean = false,
    val memberRevisionAfter: Long? = null,
    val recipientsAfter: List<String> = emptyList(),
)
