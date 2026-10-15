package com.maodouchat.server.service

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.repository.ConversationCreationRejected
import com.maodouchat.server.repository.ConversationCreationRepository
import com.maodouchat.server.repository.CreatedConversation
import com.maodouchat.server.repository.getOrCreateDirect
import com.maodouchat.server.repository.validateParticipants
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
    internal val creationRepository: ConversationCreationRepository,
    internal val invitationService: GroupInvitationService,
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

    /**
     * 幂等打开或创建 1:1 会话（与校验型 [create] 不同：重复调用返回同一会话，
     * 用于机器人私聊等人机直达入口）。pairKey 唯一约束 + 唯一冲突回读保证并发安全。
     */
    fun getOrCreateDirect(userId1: String, userId2: String): CreatedConversation =
        creationRepository.getOrCreateDirect(userId1, userId2)

    internal companion object {
        const val MAX_USER_ID_LENGTH = 50
        const val MAX_GROUP_NAME_LENGTH = 50
        val CHAT_TYPES = setOf(ChatType.DIRECT, ChatType.GROUP, ChatType.CHANNEL, ChatType.SECRET)
        val DIRECT_TYPES = setOf(ChatType.DIRECT, ChatType.SECRET)
        val GROUP_TYPES = setOf(ChatType.GROUP, ChatType.CHANNEL)
    }
}
