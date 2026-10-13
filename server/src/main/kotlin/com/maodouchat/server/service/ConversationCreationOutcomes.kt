package com.maodouchat.server.service

import com.maodouchat.server.repository.ConversationCreationFailure
import com.maodouchat.server.repository.ConversationCreationRejected
import com.maodouchat.server.repository.ConversationParticipantValidation
import com.maodouchat.server.repository.GroupInviteResult
import com.maodouchat.server.repository.GroupMemberMutationResult

/** 仓储层校验/拒绝/邀请结果 → 对外统一 outcome。纯映射，不碰服务状态。 */
internal fun ConversationParticipantValidation.toOutcome(chatType: String): CreateConversationOutcome =
    when (failure) {
        ConversationCreationFailure.PARTICIPANT_NOT_FOUND -> CreateConversationOutcome(
            CreateConversationResult.PARTICIPANT_NOT_FOUND,
            chatType = chatType,
            missingUserId = missingUserId,
        )
        ConversationCreationFailure.PARTICIPANT_BLOCKED -> CreateConversationOutcome(
            CreateConversationResult.PARTICIPANT_BLOCKED,
            chatType = chatType,
        )
        else -> CreateConversationOutcome(CreateConversationResult.INVITATION_FAILED, chatType = chatType)
    }

internal fun ConversationCreationRejected.toOutcome(chatType: String): CreateConversationOutcome = when (failure) {
    ConversationCreationFailure.PARTICIPANT_NOT_FOUND -> CreateConversationOutcome(
        CreateConversationResult.PARTICIPANT_NOT_FOUND,
        chatType = chatType,
        missingUserId = userId,
    )
    ConversationCreationFailure.PARTICIPANT_BLOCKED -> CreateConversationOutcome(
        CreateConversationResult.PARTICIPANT_BLOCKED,
        chatType = chatType,
    )
    ConversationCreationFailure.DIRECT_SELF -> CreateConversationOutcome(
        CreateConversationResult.SELF_DIRECT,
        chatType = chatType,
    )
    ConversationCreationFailure.PARTICIPANTS_EMPTY -> CreateConversationOutcome(
        CreateConversationResult.EMPTY_PARTICIPANTS,
        chatType = chatType,
    )
    ConversationCreationFailure.CREATOR_NOT_PARTICIPANT -> CreateConversationOutcome(
        CreateConversationResult.INVALID_PARTICIPANT,
        chatType = chatType,
    )
}

internal fun GroupInviteResult.toCreationOutcome(chatType: String): CreateConversationOutcome = when (result) {
    GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED ->
        CreateConversationOutcome(CreateConversationResult.MEMBER_LIMIT_EXCEEDED, chatType = chatType)
    GroupMemberMutationResult.USER_NOT_FOUND -> CreateConversationOutcome(
        CreateConversationResult.PARTICIPANT_NOT_FOUND,
        chatType = chatType,
        missingUserId = missingUserId,
    )
    GroupMemberMutationResult.BLOCKED ->
        CreateConversationOutcome(CreateConversationResult.PARTICIPANT_BLOCKED, chatType = chatType)
    else -> CreateConversationOutcome(CreateConversationResult.INVITATION_FAILED, chatType = chatType)
}
