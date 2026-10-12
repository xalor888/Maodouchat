package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/** 群成员只读：成员 id、revision、行锁。 */

/** 成员 id 查询收敛至只读边界 ConversationParticipantRepository。 */
fun GroupMembershipRepository.participantIds(chatId: String): List<String> = participantQuery.participantIds(chatId)

fun GroupMembershipRepository.memberRevision(chatId: String): Long? = transaction {
    Chats.select(Chats.memberRevision)
        .where { Chats.id eq chatId }
        .firstOrNull()
        ?.get(Chats.memberRevision)
}

internal fun GroupMembershipRepository.lockChat(chatId: String) = transaction {
    Chats.select(Chats.id).where { Chats.id eq chatId }.forUpdate().firstOrNull()
    Unit
}
