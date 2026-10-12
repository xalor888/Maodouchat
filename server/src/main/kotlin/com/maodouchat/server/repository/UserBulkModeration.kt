package com.maodouchat.server.repository

import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import com.maodouchat.server.config.AdminAccess
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update


fun UserRepository.applyBulkDisposition(
    actorId: String,
    ids: List<String>,
    field: UserRepository.UserDispositionField,
    mode: UserRepository.UserDispositionMode,
    until: Long,
    action: String,
    detailFor: (effective: Long) -> String,
): UserRepository.BulkDispositionResult {
    val existing = transaction {
        Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
    }
    val updated = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    transaction {
        ids.forEach { id ->
            if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                skipped += id
                return@forEach
            }
            val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
            if (row == null) {
                skipped += id
                return@forEach
            }
            val effective = when (mode) {
                UserRepository.UserDispositionMode.CLEAR -> 0L
                UserRepository.UserDispositionMode.SET -> until
                UserRepository.UserDispositionMode.EXTEND -> if (until <= 0L) 0L else maxOf(currentDisposition(row, field), until)
            }
            Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                when (field) {
                    UserRepository.UserDispositionField.SUSPEND -> it[Users.suspendedUntil] = effective
                    UserRepository.UserDispositionField.POST -> it[Users.postRestrictedUntil] = effective
                    UserRepository.UserDispositionField.MESSAGE -> it[Users.messageRestrictedUntil] = effective
                    UserRepository.UserDispositionField.MESSAGE_AND_POST -> {
                        it[Users.messageRestrictedUntil] = effective
                        it[Users.postRestrictedUntil] = effective
                    }
                    UserRepository.UserDispositionField.ALL -> {
                        it[Users.messageRestrictedUntil] = effective
                        it[Users.postRestrictedUntil] = effective
                        it[Users.suspendedUntil] = effective
                    }
                }
            }
            ModerationAuditLog.insert {
                it[ModerationAuditLog.userId] = id
                it[ModerationAuditLog.action] = action
                it[ModerationAuditLog.detail] = detailFor(effective).take(200)
                it[ModerationAuditLog.actorId] = actorId
                it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
            }
            updated += id
        }
    }
    return UserRepository.BulkDispositionResult(updated, skipped)
}



internal fun UserRepository.currentDisposition(row: ResultRow, field: UserRepository.UserDispositionField): Long = when (field) {
    UserRepository.UserDispositionField.SUSPEND -> row[Users.suspendedUntil]
    UserRepository.UserDispositionField.POST -> row[Users.postRestrictedUntil]
    UserRepository.UserDispositionField.MESSAGE -> row[Users.messageRestrictedUntil]
    UserRepository.UserDispositionField.MESSAGE_AND_POST -> maxOf(row[Users.messageRestrictedUntil], row[Users.postRestrictedUntil])
    UserRepository.UserDispositionField.ALL -> maxOf(row[Users.suspendedUntil], row[Users.postRestrictedUntil], row[Users.messageRestrictedUntil])
}



/** B13：批量布尔开关统一命令——存在性检查 + 单事务写入，无逐 id 审计（路由统一 count 审计）。 */
fun UserRepository.applyBulkUserSettings(
    actorId: String,
    ids: List<String>,
    field: UserRepository.UserSettingsField,
    value: Boolean,
): UserRepository.BulkDispositionResult {
    val existing = transaction {
        Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
    }
    val updated = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    transaction {
        ids.forEach { id ->
            if (shouldSkipBulk(actorId, id, existing, skipSelf = true)) {
                skipped += id
                return@forEach
            }
            Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                when (field) {
                    UserRepository.UserSettingsField.SEARCHABLE -> it[Users.searchable] = value
                    UserRepository.UserSettingsField.SHOW_STATUS -> it[Users.showStatus] = value
                    UserRepository.UserSettingsField.SHOW_ONLINE -> it[Users.showOnline] = value
                }
            }
            updated += id
        }
    }
    return UserRepository.BulkDispositionResult(updated, skipped)
}



/** B13：批量关闭 TOTP——允许对自身（含主管理员自己）操作，仅跳过其他主管理员。 */
fun UserRepository.applyBulkDisableTotp(actorId: String, ids: List<String>): UserRepository.BulkDispositionResult {
    val existing = transaction {
        Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
    }
    val updated = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    transaction {
        ids.forEach { id ->
            if (shouldSkipBulk(actorId, id, existing, skipSelf = false)) {
                skipped += id
                return@forEach
            }
            Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                it[Users.totpSecret] = null
                it[Users.totpEnabled] = false
            }
            updated += id
        }
    }
    return UserRepository.BulkDispositionResult(updated, skipped)
}



internal fun UserRepository.shouldSkipBulk(actorId: String, id: String, existing: Set<String>, skipSelf: Boolean): Boolean {
    if (id !in existing) return true
    return if (skipSelf) (id == actorId || AdminAccess.isAdmin(id)) else (AdminAccess.isAdmin(id) && id != actorId)
}
