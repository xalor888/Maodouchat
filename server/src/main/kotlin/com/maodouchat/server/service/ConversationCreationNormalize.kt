package com.maodouchat.server.service

import com.maodouchat.server.model.ChatType

/** 命令校验与归一化：类型合法、参与者去重/限长、群名/人数上限。 */
internal fun ConversationCreationService.normalize(
    actorId: String,
    command: CreateConversationCommand,
    maxGroupMembers: Int,
    maxChannelMembers: Int,
): NormalizedCommand {
    val chatType = command.chatType?.trim()?.takeIf(String::isNotEmpty)
        ?: if (command.isGroup) ChatType.GROUP else ChatType.DIRECT
    if (chatType !in ConversationCreationService.CHAT_TYPES) {
        return NormalizedCommand.Rejected(CreateConversationResult.INVALID_TYPE)
    }
    if (command.isGroup && chatType in ConversationCreationService.DIRECT_TYPES) {
        return NormalizedCommand.Rejected(CreateConversationResult.INVALID_TYPE_SHAPE)
    }
    val participantIds = command.participantIds.map(String::trim)
    if (participantIds.any { it.isEmpty() || it.length > ConversationCreationService.MAX_USER_ID_LENGTH }) {
        return NormalizedCommand.Rejected(CreateConversationResult.INVALID_PARTICIPANT)
    }
    if (participantIds.distinct().size != participantIds.size) {
        return NormalizedCommand.Rejected(CreateConversationResult.DUPLICATE_PARTICIPANT)
    }
    val isGroupConversation = chatType in ConversationCreationService.GROUP_TYPES
    if (!isGroupConversation && participantIds.isEmpty()) {
        return NormalizedCommand.Rejected(CreateConversationResult.EMPTY_PARTICIPANTS)
    }
    if (!isGroupConversation && actorId in participantIds) {
        return NormalizedCommand.Rejected(CreateConversationResult.SELF_DIRECT)
    }
    val allParticipants = (participantIds + actorId).distinct()
    if (!isGroupConversation && allParticipants.size != 2) {
        return NormalizedCommand.Rejected(CreateConversationResult.DIRECT_MEMBER_COUNT)
    }
    val groupName = command.groupName?.trim()?.takeIf(String::isNotEmpty)
    if (isGroupConversation && groupName != null && groupName.length > ConversationCreationService.MAX_GROUP_NAME_LENGTH) {
        return NormalizedCommand.Rejected(CreateConversationResult.GROUP_NAME_TOO_LONG)
    }
    val memberLimit = if (chatType == ChatType.CHANNEL) maxChannelMembers else maxGroupMembers
    if (isGroupConversation && allParticipants.size > memberLimit.coerceAtLeast(0)) {
        return NormalizedCommand.Rejected(CreateConversationResult.MEMBER_LIMIT_EXCEEDED)
    }
    return NormalizedCommand.Valid(
        chatType = chatType,
        groupName = groupName,
        allParticipantIds = allParticipants,
        inviteeIds = participantIds.filterNot { it == actorId },
    )
}
