package com.maodouchat.server.repository

import com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update


fun UserRepository.applyUserSuspension(actorId: String, userId: String, until: Long, auditDetail: String): Boolean = transaction {
    val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
        it[suspendedUntil] = until
    }
    if (changed == 0) return@transaction false
    ModerationAuditLog.insert {
        it[ModerationAuditLog.userId] = userId
        it[ModerationAuditLog.action] = "ADMIN_STATUS_UPDATE"
        it[ModerationAuditLog.detail] = auditDetail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
        it[ModerationAuditLog.actorId] = actorId
        it[createdAt] = System.currentTimeMillis()
    }
    true
}



fun UserRepository.applyUserPostRestriction(actorId: String, userId: String, until: Long, auditDetail: String): Boolean = transaction {
    val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
        it[postRestrictedUntil] = until
    }
    if (changed == 0) return@transaction false
    ModerationAuditLog.insert {
        it[ModerationAuditLog.userId] = userId
        it[ModerationAuditLog.action] = "ADMIN_POST_RESTRICT"
        it[ModerationAuditLog.detail] = auditDetail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
        it[ModerationAuditLog.actorId] = actorId
        it[createdAt] = System.currentTimeMillis()
    }
    true
}



fun UserRepository.applyUserMessageRestriction(actorId: String, userId: String, until: Long, auditDetail: String): Boolean = transaction {
    val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
        it[messageRestrictedUntil] = until
    }
    if (changed == 0) return@transaction false
    ModerationAuditLog.insert {
        it[ModerationAuditLog.userId] = userId
        it[ModerationAuditLog.action] = "ADMIN_MESSAGE_RESTRICT"
        it[ModerationAuditLog.detail] = auditDetail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
        it[ModerationAuditLog.actorId] = actorId
        it[createdAt] = System.currentTimeMillis()
    }
    true
}
