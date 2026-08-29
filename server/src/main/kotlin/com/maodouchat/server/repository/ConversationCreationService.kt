package com.maodouchat.server.repository

import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.transactions.transaction

data class CreateConversationCommand(
    val participantIds: List<String>,
    val isGroup: Boolean,
    val groupName: String?,
    val chatType: String?,
)

enum class CreateConversationResult {
    CREATED,
    INVALID_TYPE,
    INVALID_TYPE_SHAPE,
    INVALID_PARTICIPANT,
    DUPLICATE_PARTICIPANT,
    EMPTY_PARTICIPANTS,
    SELF_DIRECT,
    DIRECT_MEMBER_COUNT,
    GROUP_NAME_TOO_LONG,
    MEMBER_LIMIT_EXCEEDED,
    PARTICIPANT_NOT_FOUND,
    PARTICIPANT_BLOCKED,
    INVITATION_FAILED,
}

data class CreateConversationOutcome(
    val result: CreateConversationResult,
    val conversationId: String? = null,
    val chatType: String? = null,
    val invitedUserIds: List<String> = emptyList(),
    val missingUserId: String? = null,
)

/** Coordinates validated, atomic conversation creation and initial group invitations. */
class ConversationCreationService(
    private val creationRepository: ConversationCreationRepository,
    private val invitationRepository: GroupInvitationRepository,
) {
    fun create(
        actorId: String,
        command: CreateConversationCommand,
        maxGroupMembers: Int,
        maxChannelMembers: Int,
    ): CreateConversationOutcome {
        val normalized = normalize(actorId, command, maxGroupMembers, maxChannelMembers)
        if (normalized is NormalizedCommand.Rejected) return normalized.outcome
        normalized as NormalizedCommand.Valid

        return try {
            transaction {
                val validation = creationRepository.validateParticipants(normalized.allParticipantIds)
                if (!validation.valid) {
                    return@transaction validation.toOutcome(normalized.chatType)
                }
                when (normalized.chatType) {
                    ChatType.DIRECT, ChatType.SECRET -> createPaired(normalized)
                    ChatType.CHANNEL -> createChannel(actorId, normalized)
                    ChatType.GROUP -> createGroup(actorId, normalized, maxGroupMembers)
                    else -> CreateConversationOutcome(CreateConversationResult.INVALID_TYPE)
                }
            }
        } catch (abort: CreationAbort) {
            abort.outcome
        } catch (rejected: ConversationCreationRejected) {
            rejected.toOutcome(normalized.chatType)
        }
    }

    private fun createPaired(command: NormalizedCommand.Valid): CreateConversationOutcome {
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

    private fun createChannel(
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

    private fun createGroup(
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
            val invitations = invitationRepository.inviteMembers(
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

    private fun normalize(
        actorId: String,
        command: CreateConversationCommand,
        maxGroupMembers: Int,
        maxChannelMembers: Int,
    ): NormalizedCommand {
        val chatType = command.chatType?.trim()?.takeIf(String::isNotEmpty)
            ?: if (command.isGroup) ChatType.GROUP else ChatType.DIRECT
        if (chatType !in CHAT_TYPES) return NormalizedCommand.Rejected(CreateConversationResult.INVALID_TYPE)
        if (command.isGroup && chatType in DIRECT_TYPES) {
            return NormalizedCommand.Rejected(CreateConversationResult.INVALID_TYPE_SHAPE)
        }
        val participantIds = command.participantIds.map(String::trim)
        if (participantIds.any { it.isEmpty() || it.length > MAX_USER_ID_LENGTH }) {
            return NormalizedCommand.Rejected(CreateConversationResult.INVALID_PARTICIPANT)
        }
        if (participantIds.distinct().size != participantIds.size) {
            return NormalizedCommand.Rejected(CreateConversationResult.DUPLICATE_PARTICIPANT)
        }
        val isGroupConversation = chatType in GROUP_TYPES
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
        if (isGroupConversation && groupName != null && groupName.length > MAX_GROUP_NAME_LENGTH) {
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

    private fun ConversationParticipantValidation.toOutcome(chatType: String): CreateConversationOutcome =
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

    private fun ConversationCreationRejected.toOutcome(chatType: String): CreateConversationOutcome = when (failure) {
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

    private fun GroupInviteResult.toCreationOutcome(chatType: String): CreateConversationOutcome = when (result) {
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

    private sealed interface NormalizedCommand {
        data class Valid(
            val chatType: String,
            val groupName: String?,
            val allParticipantIds: List<String>,
            val inviteeIds: List<String>,
        ) : NormalizedCommand

        data class Rejected(val outcome: CreateConversationOutcome) : NormalizedCommand {
            constructor(result: CreateConversationResult) : this(CreateConversationOutcome(result))
        }
    }

    private class CreationAbort(val outcome: CreateConversationOutcome) : RuntimeException()

    private companion object {
        const val MAX_USER_ID_LENGTH = 50
        const val MAX_GROUP_NAME_LENGTH = 50
        val CHAT_TYPES = setOf(ChatType.DIRECT, ChatType.GROUP, ChatType.CHANNEL, ChatType.SECRET)
        val DIRECT_TYPES = setOf(ChatType.DIRECT, ChatType.SECRET)
        val GROUP_TYPES = setOf(ChatType.GROUP, ChatType.CHANNEL)
    }
}
