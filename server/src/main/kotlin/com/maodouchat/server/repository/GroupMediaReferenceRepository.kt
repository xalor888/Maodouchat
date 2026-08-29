package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/** Database references used by account cleanup and orphaned group-avatar collection. */
class GroupMediaReferenceRepository {
    fun avatarUrlsForParticipant(userId: String): List<String> = transaction {
        (ChatParticipants innerJoin Chats)
            .select(Chats.groupAvatar)
            .where { (ChatParticipants.userId eq userId) and (Chats.isGroup eq true) }
            .mapNotNull { it[Chats.groupAvatar] }
            .distinct()
    }

    fun isAvatarUrlReferenced(url: String): Boolean = transaction {
        Chats.select(Chats.id).where { Chats.groupAvatar eq url }.limit(1).any()
    }

    fun allReferencedAvatarFilenames(): Set<String> = transaction {
        Chats.select(Chats.groupAvatar).mapNotNullTo(linkedSetOf()) { row ->
            row[Chats.groupAvatar]?.substringAfterLast('/')
        }
    }
}
