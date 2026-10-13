package com.maodouchat.server.repository

import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import org.jetbrains.exposed.sql.ResultRow

// RiskEvents.matched 列宽 280；ModerationRuleRepository 里那份是 private companion 取不到，写入侧独立声明。
internal const val MAX_RISK_EVENT_MATCHED_LENGTH = 280

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
}

internal fun ResultRow.toTagRow(userCount: Long): UserTagRepository.TagRow = UserTagRepository.TagRow(
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

internal fun ResultRow.toAssignmentRow(): UserTagRepository.AssignmentRow = UserTagRepository.AssignmentRow(
    userId = this[UserTagAssignments.userId],
    tagId = this[UserTagAssignments.tagId],
    source = this[UserTagAssignments.assignmentSource],
    assignedBy = this[UserTagAssignments.assignedBy],
    createdAt = this[UserTagAssignments.createdAt]
)
