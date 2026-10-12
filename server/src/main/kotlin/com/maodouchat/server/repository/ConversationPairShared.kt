package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.SecretChatPairs
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.lockUsersInTx
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.service.DisappearingMessagePolicy
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/** 配对共享 helpers：双人会话创建、存活与完整性检查（internal，供 direct/secret 簇使用）。 */

internal fun ConversationCreationRepository.createPairedConversationInTx(
    pairKey: String,
    userId1: String,
    userId2: String,
    secret: Boolean,
): CreatedConversation {
    val chatId = if (secret) "s_${UUID.randomUUID()}" else "c_${UUID.randomUUID()}"
    val now = System.currentTimeMillis()
    Chats.insert {
        it[id] = chatId
        it[isGroup] = false
        it[chatType] = if (secret) ChatType.SECRET else ChatType.DIRECT
        it[groupName] = null
        it[memberRevision] = 0
        if (secret) {
            it[disappearingMessageSeconds] = DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS
        }
    }
    listOf(userId1, userId2).forEach { userId ->
        ChatParticipants.insert {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = userId
            it[joinedAt] = now
            it[role] = ROLE_MEMBER
        }
    }
    if (secret) {
        SecretChatPairs.insert {
            it[SecretChatPairs.pairKey] = pairKey
            it[SecretChatPairs.chatId] = chatId
            it[createdAt] = now
        }
    } else {
        DirectChatPairs.insert {
            it[DirectChatPairs.pairKey] = pairKey
            it[DirectChatPairs.chatId] = chatId
            it[createdAt] = now
        }
    }
    return CreatedConversation(chatId)
}

internal fun ConversationCreationRepository.hasActivePairInTx(userId1: String, userId2: String): Boolean {
    val users = lockUsersInTx(listOf(userId1, userId2))
    return users.size == 2 && users.none { it[Users.deletedAt] != null }
}

internal fun ConversationCreationRepository.isIntactPairInTx(
    chatId: String,
    userId1: String,
    userId2: String,
    secret: Boolean,
): Boolean {
    val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull() ?: return false
    if (chat[Chats.isGroup]) return false
    if ((chat[Chats.chatType] == ChatType.SECRET) != secret) return false
    val members = ChatParticipants.selectAll()
        .where { ChatParticipants.chatId eq chatId }
        .mapTo(hashSetOf()) { it[ChatParticipants.userId] }
    return members.size == 2 && userId1 in members && userId2 in members
}
