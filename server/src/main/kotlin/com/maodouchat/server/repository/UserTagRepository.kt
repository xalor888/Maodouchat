package com.maodouchat.server.repository

import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * 用户标签仓储：运营给用户打的风险/分群标签，与风控联动。
 * 只存元数据，不存消息明文。
 */
class UserTagRepository {

    data class TagRow(
        val id: String,
        val name: String,
        val color: String,
        val description: String?,
        val isSystem: Boolean,
        val riskLevel: String,
        val createdAt: Long,
        val updatedAt: Long,
        val userCount: Long = 0
    )

    data class AssignmentRow(
        val userId: String,
        val tagId: String,
        val source: String,
        val assignedBy: String?,
        val createdAt: Long
    )

    fun createTag(
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

    fun getTag(id: String): TagRow? = transaction {
        UserTags.selectAll().where { UserTags.id eq id }.firstOrNull()?.let { it.toTagRow(0) }
    }

    fun listTags(query: String? = null): List<TagRow> = transaction {
        val base = if (query?.isNotBlank() == true) {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            UserTags.selectAll().where {
                (UserTags.name like "%$escaped%") or (UserTags.description like "%$escaped%")
            }
        } else UserTags.selectAll()
        val rows = base.orderBy(UserTags.createdAt to SortOrder.ASC).toList()
        // 8.48 修复 M8：批量 count 赋值（此前逐标签查 count → N+1）
        val tagIds = rows.map { it[UserTags.id] }
        val countByTag = if (tagIds.isEmpty()) emptyMap() else
            UserTagAssignments
                .slice(UserTagAssignments.tagId, UserTagAssignments.userId.count())
                .selectAll()
                .where { UserTagAssignments.tagId inList tagIds }
                .groupBy(UserTagAssignments.tagId)
                .associate { it[UserTagAssignments.tagId] to it[UserTagAssignments.userId.count()].toInt() }
        rows.map { row -> row.toTagRow(countByTag[row[UserTags.id]] ?: 0) }
    }

    fun updateTag(
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
    fun deleteTag(id: String): Boolean = transaction {
        val row = UserTags.selectAll().where { UserTags.id eq id }.firstOrNull() ?: return@transaction false
        if (row[UserTags.isSystem]) return@transaction false
        UserTagAssignments.deleteWhere { UserTagAssignments.tagId eq id }
        UserTags.deleteWhere { UserTags.id eq id }
        true
    }

    /** 标签下的用户列表（可按用户名/邮箱搜索，分页）。 */
    fun listUsersByTag(tagId: String, query: String?, limit: Int, offset: Long): List<AssignmentRow> = transaction {
        val matchingUserIds = if (query?.isNotBlank() == true) {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            Users.selectAll().where {
                (Users.name like "%$escaped%") or (Users.email like "%$escaped%")
            }.map { it[Users.id] }.toSet()
        } else null
        if (matchingUserIds != null && matchingUserIds.isEmpty()) return@transaction emptyList()
        UserTagAssignments.selectAll().where {
            UserTagAssignments.tagId eq tagId
        }.let { q ->
            if (matchingUserIds != null && matchingUserIds.isNotEmpty()) {
                q.andWhere { UserTagAssignments.userId inList matchingUserIds }
            } else q
        }.orderBy(UserTagAssignments.createdAt to SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toAssignmentRow() }
    }

    fun assignTags(
        userId: String,
        tagIds: List<String>,
        source: String,
        assignedBy: String?
    ): List<AssignmentRow> = transaction {
        val now = System.currentTimeMillis()
        val ids = tagIds.distinct().filter { tagId ->
            UserTags.selectAll().where { UserTags.id eq tagId }.firstOrNull() != null
        }
        ids.forEach { tagId ->
            val existing = UserTagAssignments.selectAll().where {
                (UserTagAssignments.tagId eq tagId) and (UserTagAssignments.userId eq userId)
            }.firstOrNull()
            if (existing == null) {
                try {
                    UserTagAssignments.insert {
                        it[UserTagAssignments.tagId] = tagId
                        it[UserTagAssignments.userId] = userId
                        it[UserTagAssignments.assignmentSource] = source.uppercase().take(20).ifBlank { "MANUAL" }
                        it[UserTagAssignments.assignedBy] = assignedBy
                        it[UserTagAssignments.createdAt] = now
                    }
                } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    // 手动打标与风控自动打标并发时可能同时插入同一 (tagId,userId) → PK 冲突。
                    // 已有行即目标状态，捕获后忽略（B8 并发加固）。
                }
            }
        }
        userAssignments(userId)
    }

    fun detachTag(userId: String, tagId: String): Boolean = transaction {
        UserTagAssignments.deleteWhere {
            (UserTagAssignments.tagId eq tagId) and (UserTagAssignments.userId eq userId)
        } > 0
    }

    fun userAssignments(userId: String): List<AssignmentRow> = transaction {
        UserTagAssignments.selectAll().where { UserTagAssignments.userId eq userId }
            .orderBy(UserTagAssignments.createdAt to SortOrder.DESC)
            .map { it.toAssignmentRow() }
    }

    fun userTagIds(userId: String): List<String> = transaction {
        UserTagAssignments.selectAll().where { UserTagAssignments.userId eq userId }
            .map { it[UserTagAssignments.tagId] }
    }

    /** 带风险级别标签的赋值：用于风控联动（风险打标自动处置）。 */
    fun riskAssignments(userId: String): List<AssignmentRow> = transaction {
        val rows = UserTagAssignments.selectAll().where { UserTagAssignments.userId eq userId }.toList()
        // 8.48 修复 M9：批量回查标签（此前 filter 内逐条查 → N+1）
        val tagIds = rows.map { it[UserTagAssignments.tagId] }.distinct()
        val riskByTag = if (tagIds.isEmpty()) emptyMap() else
            UserTags.selectAll().where { UserTags.id inList tagIds }
                .associate { it[UserTags.id] to (it[UserTags.riskLevel] in setOf("HIGH", "CRITICAL")) }
        rows.filter { riskByTag[it[UserTagAssignments.tagId]] == true }.map { it.toAssignmentRow() }
    }

    private fun ResultRow.toTagRow(userCount: Long): TagRow = TagRow(
        id = this[UserTags.id],
        name = this[UserTags.name],
        color = this[UserTags.color],
        description = this[UserTags.description],
        isSystem = this[UserTags.isSystem],
        riskLevel = this[UserTags.riskLevel],
        createdAt = this[UserTags.createdAt],
        updatedAt = this[UserTags.updatedAt],
        userCount = userCount
    )

    private fun ResultRow.toAssignmentRow(): AssignmentRow = AssignmentRow(
        userId = this[UserTagAssignments.userId],
        tagId = this[UserTagAssignments.tagId],
        source = this[UserTagAssignments.assignmentSource],
        assignedBy = this[UserTagAssignments.assignedBy],
        createdAt = this[UserTagAssignments.createdAt]
    )
}
