package com.maodouchat.server.repository

// 用户处置/封禁/批量管理簇：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.*
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

/**
 * B11：管理员处置统一入口——写字段 + 审计同一事务（actor/reason/before-after 入 audit detail）。
 * 替代路由层散落的裸 Users.update + ModerationAuditLog.insert。
 */
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

/**
 * B13：批量处置统一命令——批量存在性检查 + 单事务逐 id 写入 + 逐 id 审计。
 * 收敛 AdminBulkRouting 里 ~15 段「skip 自己/主管理员/不存在 → update → audit」重复实现。
 */
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
