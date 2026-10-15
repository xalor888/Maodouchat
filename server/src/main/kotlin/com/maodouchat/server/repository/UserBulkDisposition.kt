package com.maodouchat.server.repository

// 用户批量处置簇：封禁/限制批量设置与当前处置查询（原 UserBulkModeration 扩展按域拆出，零行为改动）。

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
    field: UserDispositionField,
    mode: UserDispositionMode,
    until: Long,
    action: String,
    detailFor: (effective: Long) -> String,
): BulkDispositionResult {
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
                UserDispositionMode.CLEAR -> 0L
                UserDispositionMode.SET -> until
                UserDispositionMode.EXTEND -> if (until <= 0L) 0L else maxOf(currentDisposition(row, field), until)
            }
            Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                when (field) {
                    UserDispositionField.SUSPEND -> it[Users.suspendedUntil] = effective
                    UserDispositionField.POST -> it[Users.postRestrictedUntil] = effective
                    UserDispositionField.MESSAGE -> it[Users.messageRestrictedUntil] = effective
                    UserDispositionField.MESSAGE_AND_POST -> {
                        it[Users.messageRestrictedUntil] = effective
                        it[Users.postRestrictedUntil] = effective
                    }
                    UserDispositionField.ALL -> {
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
    return BulkDispositionResult(updated, skipped)
}

internal fun UserRepository.currentDisposition(row: ResultRow, field: UserDispositionField): Long = when (field) {
    UserDispositionField.SUSPEND -> row[Users.suspendedUntil]
    UserDispositionField.POST -> row[Users.postRestrictedUntil]
    UserDispositionField.MESSAGE -> row[Users.messageRestrictedUntil]
    UserDispositionField.MESSAGE_AND_POST -> maxOf(row[Users.messageRestrictedUntil], row[Users.postRestrictedUntil])
    UserDispositionField.ALL -> maxOf(row[Users.suspendedUntil], row[Users.postRestrictedUntil], row[Users.messageRestrictedUntil])
}
