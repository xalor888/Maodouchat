package com.maodouchat.server.repository

// 用户批量安全簇：批量关闭 TOTP 与批量跳过判定（原 UserBulkModeration 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Users
import com.maodouchat.server.config.AdminAccess
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

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
