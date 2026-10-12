package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update


fun UserRepository.isModerator(userId: String): Boolean {
    return transaction {
        Users.selectAll().where { Users.id eq userId }
            .firstOrNull()
            ?.takeIf { it[Users.deletedAt] == null }
            ?.get(Users.isModerator) == true
    }
}



fun UserRepository.getMessageRestrictionUntil(userId: String): Long = getRestrictionUntil(userId, Restriction.MESSAGE)



fun UserRepository.getPostRestrictionUntil(userId: String): Long = getRestrictionUntil(userId, Restriction.POST)



fun UserRepository.getSuspendedUntil(userId: String): Long = getRestrictionUntil(userId, Restriction.SUSPEND)



fun UserRepository.applyModerationRestriction(userId: String, action: String): Long? {
    val normalizedAction = action.trim().uppercase()
    val duration = when (normalizedAction) {
        "RESTRICT_MESSAGES_24H" -> 24L * 60L * 60L * 1000L
        "RESTRICT_POSTS_7D" -> 7L * 24L * 60L * 60L * 1000L
        "SUSPEND_24H" -> 24L * 60L * 60L * 1000L
        else -> return null
    }
    val now = System.currentTimeMillis()
    val until = now + duration
    return transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull()
            ?.takeIf { it[Users.deletedAt] == null } ?: return@transaction null
        Users.update({ Users.id eq userId }) {
            when (normalizedAction) {
                "RESTRICT_MESSAGES_24H" -> it[Users.messageRestrictedUntil] = maxOf(row[Users.messageRestrictedUntil], until)
                "RESTRICT_POSTS_7D" -> it[Users.postRestrictedUntil] = maxOf(row[Users.postRestrictedUntil], until)
                "SUSPEND_24H" -> it[Users.suspendedUntil] = maxOf(row[Users.suspendedUntil], until)
            }
        }
        until
    }
}



internal fun UserRepository.getRestrictionUntil(userId: String, restriction: Restriction): Long {
    return transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull()
            ?.takeIf { it[Users.deletedAt] == null } ?: return@transaction 0L
        when (restriction) {
            Restriction.MESSAGE -> row[Users.messageRestrictedUntil]
            Restriction.POST -> row[Users.postRestrictedUntil]
            Restriction.SUSPEND -> row[Users.suspendedUntil]
        }.takeIf { it > System.currentTimeMillis() } ?: 0L
    }
}
