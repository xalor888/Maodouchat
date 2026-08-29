package com.maodouchat.ui.screen.chatdetail

internal fun isActiveChatEvent(activeChatId: String, eventChatId: String): Boolean =
    activeChatId.isNotBlank() && eventChatId == activeChatId

internal fun shouldApplyContactPresence(
    isGroupChat: Boolean,
    contactId: String,
    eventUserId: String
): Boolean = !isGroupChat && contactId.isNotBlank() && contactId == eventUserId

internal enum class GroupRevisionImpact { IGNORE, REFRESH, CURRENT_USER_REMOVED }

internal fun groupRevisionImpact(
    activeChatId: String,
    currentUserId: String,
    eventChatId: String,
    targetUserId: String?,
    reason: String
): GroupRevisionImpact = when {
    !isActiveChatEvent(activeChatId, eventChatId) -> GroupRevisionImpact.IGNORE
    currentUserId.isNotBlank() &&
        targetUserId == currentUserId &&
        reason in MEMBER_REMOVAL_REASONS -> GroupRevisionImpact.CURRENT_USER_REMOVED
    else -> GroupRevisionImpact.REFRESH
}

internal fun shouldInvalidateGroupKey(
    currentRevision: Long,
    eventRevision: Long,
    impact: GroupRevisionImpact
): Boolean = impact == GroupRevisionImpact.CURRENT_USER_REMOVED || eventRevision > currentRevision

private val MEMBER_REMOVAL_REASONS = setOf("MEMBER_REMOVED", "MEMBER_LEFT")
