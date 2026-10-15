package com.maodouchat.server.repository

import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

fun UserTagRepository.createTag(
    name: String,
    color: String,
    description: String?,
    riskLevel: String,
    isSystem: Boolean,
    createdBy: String?
): TagRow = transaction {
    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    UserTags.insert {
        it[UserTags.id] = id
        it[UserTags.name] = name.trim().take(60)
        it[UserTags.color] = color.take(20).ifBlank { "#64748b" }
        it[UserTags.description] = description?.take(300)
        it[UserTags.isSystem] = isSystem
        it[UserTags.riskLevel] = riskLevel.uppercase().take(20)
        it[UserTags.createdBy] = createdBy
        it[UserTags.createdAt] = now
        it[UserTags.updatedAt] = now
    }
    getTag(id) ?: error("user tag insert failed")
}

fun UserTagRepository.getTag(id: String): TagRow? = transaction {
    UserTags.selectAll().where { UserTags.id eq id }.firstOrNull()?.let { it.toTagRow(0) }
}

fun UserTagRepository.listTags(query: String? = null): List<TagRow> = transaction {
    val base = if (query?.isNotBlank() == true) {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        UserTags.selectAll().where {
            (UserTags.name like "%$escaped%") or (UserTags.description like "%$escaped%")
        }
    } else UserTags.selectAll()
    val rows = base.orderBy(UserTags.createdAt to SortOrder.ASC).toList()
    // 批量 count 赋值，避免逐标签查 count 的 N+1
    val tagIds = rows.map { it[UserTags.id] }
    val countByTag = if (tagIds.isEmpty()) emptyMap() else
        UserTagAssignments
            .select(UserTagAssignments.tagId, UserTagAssignments.userId.count())
            .where { UserTagAssignments.tagId inList tagIds }
            .groupBy(UserTagAssignments.tagId)
            .associate { it[UserTagAssignments.tagId] to it[UserTagAssignments.userId.count()] }
    rows.map { row -> row.toTagRow(countByTag[row[UserTags.id]] ?: 0L) }
}

fun UserTagRepository.updateTag(
    id: String,
    name: String?,
    color: String?,
    description: String?,
    riskLevel: String?
): TagRow? = transaction {
    val row = UserTags.selectAll().where { UserTags.id eq id }.firstOrNull() ?: return@transaction null
    val now = System.currentTimeMillis()
    UserTags.update({ UserTags.id eq id }) {
        name?.let { n -> it[UserTags.name] = n.trim().take(60) }
        color?.let { c -> it[UserTags.color] = c.take(20).ifBlank { "#64748b" } }
        it[UserTags.description] = description?.take(300)
        riskLevel?.let { r -> it[UserTags.riskLevel] = r.uppercase().take(20) }
        it[UserTags.updatedAt] = now
    }
    getTag(id)
}

/** 删除标签（级联移除所有赋值）。系统内置标签不可删除。 */
fun UserTagRepository.deleteTag(id: String): Boolean = transaction {
    val row = UserTags.selectAll().where { UserTags.id eq id }.firstOrNull() ?: return@transaction false
    if (row[UserTags.isSystem]) return@transaction false
    UserTagAssignments.deleteWhere { UserTagAssignments.tagId eq id }
    UserTags.deleteWhere { UserTags.id eq id }
    true
}
