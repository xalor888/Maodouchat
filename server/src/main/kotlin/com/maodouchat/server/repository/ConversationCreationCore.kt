package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.model.ChatType
import java.util.UUID
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/** 会话创建核心簇：群/单聊创建入口。 */

fun ConversationCreationRepository.create(
    participantIds: List<String>,
    isGroup: Boolean = false,
    groupName: String? = null,
    creatorId: String? = null,
    chatType: String = if (isGroup) ChatType.GROUP else ChatType.DIRECT,
): CreatedConversation = transaction {
    val uniqueParticipantIds = participantIds.distinct()
    if (uniqueParticipantIds.isEmpty()) reject(ConversationCreationFailure.PARTICIPANTS_EMPTY)
    if (creatorId != null && creatorId !in uniqueParticipantIds) {
        reject(ConversationCreationFailure.CREATOR_NOT_PARTICIPANT, creatorId)
    }
    validateParticipantsInTx(uniqueParticipantIds).throwIfInvalid()
    val chatId = "c_${UUID.randomUUID()}"
    Chats.insert {
        it[id] = chatId
        it[Chats.isGroup] = isGroup
        it[Chats.chatType] = chatType
        it[Chats.groupName] = groupName
        it[memberRevision] = if (isGroup) 1 else 0
    }
    val now = System.currentTimeMillis()
    ChatParticipants.batchInsert(uniqueParticipantIds) { userId ->
        this[ChatParticipants.chatId] = chatId
        this[ChatParticipants.userId] = userId
        this[ChatParticipants.joinedAt] = now
        this[ChatParticipants.role] = if (
            isGroup && userId == (creatorId ?: uniqueParticipantIds.first())
        ) ROLE_OWNER else ROLE_MEMBER
    }
    CreatedConversation(chatId)
}

internal const val ROLE_MEMBER = "MEMBER"
