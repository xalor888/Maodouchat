package com.maodouchat.server.repository

import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import com.maodouchat.server.service.disableTotp



/** B13：授予/撤销 moderator——写字段 + 审计同一事务（以 update 影响行数为准）。 */
fun UserRepository.setModerator(actorId: String, userId: String, enabled: Boolean): Boolean = transaction {
    val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
        it[Users.isModerator] = enabled
    }
    if (changed == 0) return@transaction false
    ModerationAuditLog.insert {
        it[ModerationAuditLog.userId] = userId
        it[ModerationAuditLog.action] = if (enabled) "ADMIN_GRANT_MODERATOR" else "ADMIN_REVOKE_MODERATOR"
        it[ModerationAuditLog.detail] = "enabled=$enabled"
        it[ModerationAuditLog.actorId] = actorId
        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
    }
    true
}



/** B13：关闭用户 TOTP——写字段 + 审计同一事务。 */
fun UserRepository.disableTotp(actorId: String, userId: String): Boolean = transaction {
    val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
        it[Users.totpSecret] = null
        it[Users.totpEnabled] = false
    }
    if (changed == 0) return@transaction false
    ModerationAuditLog.insert {
        it[ModerationAuditLog.userId] = userId
        it[ModerationAuditLog.action] = "ADMIN_DISABLE_TOTP"
        it[ModerationAuditLog.detail] = "admin disabled totp"
        it[ModerationAuditLog.actorId] = actorId
        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
    }
    true
}
