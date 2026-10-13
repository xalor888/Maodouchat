package com.maodouchat.server.repository

import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** 标签下的用户列表（可按用户名/邮箱搜索，分页）。 */
fun UserTagRepository.listUsersByTag(
    tagId: String,
    query: String?,
    limit: Int,
    offset: Long
): List<UserTagRepository.AssignmentRow> = transaction {
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
    }.orderBy(
        UserTagAssignments.createdAt to SortOrder.DESC,
        UserTagAssignments.tagId to SortOrder.DESC,
        UserTagAssignments.userId to SortOrder.DESC
    )
        .limit(limit, offset)
        .map { it.toAssignmentRow() }
}

fun UserTagRepository.assignTags(
    userId: String,
    tagIds: List<String>,
    source: String,
    assignedBy: String?
): List<UserTagRepository.AssignmentRow>? = transaction {
    val now = System.currentTimeMillis()
    val distinctTagIds = tagIds.distinct()
    val existingTagIds = UserTags.selectAll()
        .where { UserTags.id inList distinctTagIds }
        .map { it[UserTags.id] }
        .toSet()
    val ids = distinctTagIds.filter { it in existingTagIds }
    // 串行化同一用户的并发打标：先 forUpdate 锁住用户行，exists 检查即准确，(tagId, userId) PK 竞态从源头消除。
    // 用户不存在返回 null（路由映射 404），避免悬空 FK 触发 500。
    Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
        ?: return@transaction null
    val existingAssignmentIds = UserTagAssignments.selectAll()
        .where {
            (UserTagAssignments.userId eq userId) and (UserTagAssignments.tagId inList ids)
        }
        .map { it[UserTagAssignments.tagId] }
        .toSet()
    val toInsert = ids.filter { it !in existingAssignmentIds }
    if (toInsert.isNotEmpty()) {
        UserTagAssignments.batchInsert(toInsert) { tagId ->
            this[UserTagAssignments.tagId] = tagId
            this[UserTagAssignments.userId] = userId
            this[UserTagAssignments.assignmentSource] = source.uppercase().take(20).ifBlank { "MANUAL" }
            this[UserTagAssignments.assignedBy] = assignedBy
            this[UserTagAssignments.createdAt] = now
        }
        // 高危标签联动写风控事件：级别/名称进 matched，标签 id 进 referenceId。
        val riskTags = UserTags.selectAll()
            .where { (UserTags.id inList toInsert) and (UserTags.riskLevel inList listOf("HIGH", "CRITICAL")) }
            .toList()
        for (t in riskTags) {
            val riskLevel = t[UserTags.riskLevel]
            val tagName = t[UserTags.name]
            RiskEvents.insert {
                it[RiskEvents.id] = UUID.randomUUID().toString()
                it[RiskEvents.userId] = userId
                it[RiskEvents.sourceValue] = "USER_TAG_ASSIGNED"
                it[RiskEvents.ruleId] = null
                it[RiskEvents.action] = "TAGGED"
                it[RiskEvents.matched] = "$riskLevel/$tagName · 操作人=${assignedBy ?: "system"}"
                    .take(MAX_RISK_EVENT_MATCHED_LENGTH)
                it[RiskEvents.referenceId] = t[UserTags.id]
                it[RiskEvents.needsReview] = true
                it[RiskEvents.createdAt] = now
            }
        }
    }
    userAssignments(userId)
}

fun UserTagRepository.detachTag(userId: String, tagId: String): Boolean = transaction {
    UserTagAssignments.deleteWhere {
        (UserTagAssignments.tagId eq tagId) and (UserTagAssignments.userId eq userId)
    } > 0
}

fun UserTagRepository.userAssignments(userId: String): List<UserTagRepository.AssignmentRow> = transaction {
    UserTagAssignments.selectAll().where { UserTagAssignments.userId eq userId }
        .orderBy(
            UserTagAssignments.createdAt to SortOrder.DESC,
            UserTagAssignments.tagId to SortOrder.DESC,
            UserTagAssignments.userId to SortOrder.DESC
        )
        .map { it.toAssignmentRow() }
}

fun UserTagRepository.userTagIds(userId: String): List<String> = transaction {
    UserTagAssignments.selectAll().where { UserTagAssignments.userId eq userId }
        .map { it[UserTagAssignments.tagId] }
}

/** 带风险级别标签的赋值：用于风控联动（风险打标自动处置）。 */
fun UserTagRepository.riskAssignments(userId: String): List<UserTagRepository.AssignmentRow> = transaction {
    val rows = UserTagAssignments.selectAll().where { UserTagAssignments.userId eq userId }.toList()
    // 批量回查标签，避免 filter 内逐条查的 N+1
    val tagIds = rows.map { it[UserTagAssignments.tagId] }.distinct()
    val riskByTag = if (tagIds.isEmpty()) emptyMap() else
        UserTags.selectAll().where { UserTags.id inList tagIds }
            .associate { it[UserTags.id] to (it[UserTags.riskLevel] in setOf("HIGH", "CRITICAL")) }
    rows.filter { riskByTag[it[UserTagAssignments.tagId]] == true }.map { it.toAssignmentRow() }
}
