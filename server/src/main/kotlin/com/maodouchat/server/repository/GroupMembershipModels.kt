package com.maodouchat.server.repository

enum class TransferOwnershipResult {
    TRANSFERRED,
    CHAT_NOT_FOUND,
    NOT_GROUP,
    NOT_OWNER,
    TARGET_NOT_PARTICIPANT,
    TARGET_DEACTIVATED,
    SAME_USER,
}

enum class GroupMemberMutationResult {
    UPDATED,
    CHAT_NOT_FOUND,
    NOT_GROUP,
    ACTOR_NOT_PARTICIPANT,
    TARGET_NOT_PARTICIPANT,
    FORBIDDEN,
    SELF_NOT_ALLOWED,
    OWNER_PROTECTED,
    PEER_ADMIN_PROTECTED,
    MEMBER_LIMIT_EXCEEDED,
    USER_NOT_FOUND,
    BLOCKED,
}

data class GroupAvatarMutationResult(
    val result: GroupMemberMutationResult,
    val previousAvatarUrl: String? = null,
)

data class GroupBulkMuteResult(
    val result: GroupMemberMutationResult,
    val updatedCount: Int = 0,
)

data class AddGroupMembersResult(
    val result: GroupMemberMutationResult,
    val addedUserIds: List<String> = emptyList(),
    val missingUserId: String? = null,
    val blockedUserId: String? = null,
)

enum class AddOwnedBotResult {
    ADDED,
    ALREADY_MEMBER,
    CHAT_NOT_FOUND,
    NOT_GROUP,
    FORBIDDEN,
    BOT_NOT_FOUND,
    BOT_NOT_OWNED,
    BOT_DISABLED,
    MEMBER_LIMIT_EXCEEDED,
}
