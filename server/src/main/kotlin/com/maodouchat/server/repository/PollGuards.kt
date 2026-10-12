package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 群投票守卫簇：成员名单（WS fanout）与群/成员/禁言校验。 */

/**
 * 群成员名单（用于 WS fanout）。与 [isMemberInTransaction]
 * 一致：仅返回 ChatParticipants 中的真实成员，避免对已退群成员推送。
 */
fun PollRepository.memberIds(chatId: String): List<String> {
    if (chatId.isBlank()) return emptyList()
    return transaction {
        ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
    }
}

/** 校验 chatId 是群聊。 */
fun PollRepository.isGroupChat(chatId: String): Boolean = transaction {
    Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()?.get(Chats.isGroup) == true
}

/** 校验 userId 是 chatId 的成员。 */
fun PollRepository.isMember(chatId: String, userId: String): Boolean = transaction {
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0
}

/** 校验 userId 在 chatId 中是否处于禁言期。 */
fun PollRepository.isMuted(chatId: String, userId: String, now: Long = System.currentTimeMillis()): Boolean = transaction {
    val mutedUntil = ChatParticipants.selectAll()
        .where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }
        .firstOrNull()
        ?.get(ChatParticipants.mutedUntil)
        ?: 0L
    mutedUntil > now
}
