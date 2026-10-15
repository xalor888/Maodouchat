package com.maodouchat.server.repository

// 用户批量设置簇：可发现性/隐私布尔开关批量写入（原 UserBulkModeration 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** B13：批量布尔开关统一命令——存在性检查 + 单事务写入，无逐 id 审计（路由统一 count 审计）。 */
fun UserRepository.applyBulkUserSettings(
    actorId: String,
    ids: List<String>,
    field: UserSettingsField,
    value: Boolean,
): BulkDispositionResult {
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
                    UserSettingsField.SEARCHABLE -> it[Users.searchable] = value
                    UserSettingsField.SHOW_STATUS -> it[Users.showStatus] = value
                    UserSettingsField.SHOW_ONLINE -> it[Users.showOnline] = value
                }
            }
            updated += id
        }
    }
    return BulkDispositionResult(updated, skipped)
}
