package com.maodouchat.server.service

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.repository.create
import com.maodouchat.server.repository.getOrCreateDirect
import com.maodouchat.server.repository.getOrCreateSecret

/** 按会话类型的实际落库：1:1（普通/密聊）幂等建、频道建、群建 + 初始邀请。 */
internal fun ConversationCreationService.createPaired(
    command: NormalizedCommand.Valid,
): CreateConversationOutcome {
    val created = if (command.chatType == ChatType.SECRET) {
        creationRepository.getOrCreateSecret(command.allParticipantIds[0], command.allParticipantIds[1])
    } else {
        creationRepository.getOrCreateDirect(command.allParticipantIds[0], command.allParticipantIds[1])
    }
    return CreateConversationOutcome(
        CreateConversationResult.CREATED,
        conversationId = created.id,
        chatType = command.chatType,
    )
}

internal fun ConversationCreationService.createChannel(
    actorId: String,
    command: NormalizedCommand.Valid,
): CreateConversationOutcome {
    val created = creationRepository.create(
        participantIds = command.allParticipantIds,
        isGroup = true,
        groupName = command.groupName,
        creatorId = actorId,
        chatType = ChatType.CHANNEL,
    )
    return CreateConversationOutcome(
        CreateConversationResult.CREATED,
        conversationId = created.id,
        chatType = ChatType.CHANNEL,
    )
}

internal fun ConversationCreationService.createGroup(
    actorId: String,
    command: NormalizedCommand.Valid,
    maxGroupMembers: Int,
): CreateConversationOutcome {
    val created = creationRepository.create(
        participantIds = listOf(actorId),
        isGroup = true,
        groupName = command.groupName,
        creatorId = actorId,
        chatType = ChatType.GROUP,
    )
    if (command.inviteeIds.isNotEmpty()) {
        val invitations = invitationService.inviteMembers(
            created.id,
            actorId,
            command.inviteeIds,
            maxGroupMembers,
        )
        if (invitations.result != GroupMemberMutationResult.UPDATED) {
            throw CreationAbort(invitations.toCreationOutcome(ChatType.GROUP))
        }
        return CreateConversationOutcome(
            CreateConversationResult.CREATED,
            conversationId = created.id,
            chatType = ChatType.GROUP,
            invitedUserIds = invitations.invitedUserIds,
        )
    }
    return CreateConversationOutcome(
        CreateConversationResult.CREATED,
        conversationId = created.id,
        chatType = ChatType.GROUP,
    )
}
