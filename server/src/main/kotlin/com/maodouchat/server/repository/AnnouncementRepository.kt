package com.maodouchat.server.repository

import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.UserTagAssignments
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
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
 * 系统公告仓储。公告为服务端广播的明文平台消息（非 E2EE 会话正文），
 * 不触碰 Messages / EncryptedAttachments 任何密文。
 */
class AnnouncementRepository {

    data class AnnouncementRow(
        val id: String,
        val title: String,
        val content: String,
        val level: String,
        val targetAudience: String,
        val targetTagId: String?,
        val startsAt: Long,
        val expiresAt: Long,
        val status: String,
        val createdBy: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val publishedAt: Long?,
        val cancelledAt: Long?
    )

    fun create(
        title: String,
        content: String,
        level: String,
        targetAudience: String,
        targetTagId: String?,
        startsAt: Long,
        expiresAt: Long,
        createdBy: String?
    ): AnnouncementRow = transaction {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val status = when {
            now >= startsAt -> "ACTIVE"
            else -> "SCHEDULED"
        }
        SystemAnnouncements.insert {
            it[SystemAnnouncements.id] = id
            it[SystemAnnouncements.title] = title.take(200)
            it[SystemAnnouncements.content] = content
            it[SystemAnnouncements.level] = level.uppercase().take(20)
            it[SystemAnnouncements.targetAudience] = targetAudience.uppercase().take(20)
            it[SystemAnnouncements.targetTagId] = targetTagId
            it[SystemAnnouncements.startsAt] = startsAt
            it[SystemAnnouncements.expiresAt] = expiresAt
            it[SystemAnnouncements.status] = status
            it[SystemAnnouncements.createdBy] = createdBy
            it[SystemAnnouncements.createdAt] = now
            it[SystemAnnouncements.updatedAt] = now
            it[SystemAnnouncements.publishedAt] = if (status == "ACTIVE") now else null
        }
        get(id) ?: error("announcement insert failed")
    }

    fun get(id: String): AnnouncementRow? = transaction {
        SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
            .firstOrNull()?.let { it.toAnnouncementRow() }
    }

    /** 管理端列表：按状态/关键词过滤，createdAt DESC 分页。 */
    fun list(
        status: String? = null,
        query: String? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<AnnouncementRow> = transaction {
        var base = SystemAnnouncements.selectAll()
        status?.takeIf { it.isNotBlank() }?.let { st ->
            base = base.andWhere { SystemAnnouncements.status eq st.uppercase().take(20) }
        }
        query?.takeIf { it.isNotBlank() }?.let { q ->
            val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            base = base.andWhere {
                (SystemAnnouncements.title like "%$escaped%") or (SystemAnnouncements.content like "%$escaped%")
            }
        }
        base.orderBy(SystemAnnouncements.createdAt to SortOrder.DESC, SystemAnnouncements.id to SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toAnnouncementRow() }
    }

    /**
     * 用户端拉取活跃公告：仅返回 status=ACTIVE 且在 [startsAt, expiresAt] 窗口内、
     * 且 audience 命中的公告。targetAudience=ALL 全员可见；
     * targetAudience=TAGGED 需用户已带 [userTagIds] 中的目标标签。
     */
    fun activeForUser(userId: String, now: Long, userTagIds: List<String>): List<AnnouncementRow> = transaction {
        val rows = SystemAnnouncements.selectAll().where {
            (SystemAnnouncements.status eq "ACTIVE") and
                (SystemAnnouncements.startsAt lessEq now) and
                (SystemAnnouncements.expiresAt greaterEq now)
        }.orderBy(SystemAnnouncements.startsAt to SortOrder.ASC)
        rows.filter { row ->
            val audience = row[SystemAnnouncements.targetAudience]
            if (audience == "ALL") return@filter true
            val tagId = row[SystemAnnouncements.targetTagId]
            tagId != null && userTagIds.contains(tagId)
        }.map { it.toAnnouncementRow() }
    }

    /** 更新草稿/调度字段；已发布（ACTIVE）公告仅允许延后/提前窗口。 */
    fun update(
        id: String,
        title: String?,
        content: String?,
        level: String?,
        targetAudience: String?,
        targetTagId: String?,
        startsAt: Long?,
        expiresAt: Long?
    ): AnnouncementRow? = transaction {
        val existing = SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
            .firstOrNull() ?: return@transaction null
        val now = System.currentTimeMillis()
        val newStartsAt = startsAt ?: existing[SystemAnnouncements.startsAt]
        val newExpiresAt = expiresAt ?: existing[SystemAnnouncements.expiresAt]
        // 8.34 修复：窗口校验兜底（路由已校验，仓库侧纵深防御）
        if (newExpiresAt > 0 && newExpiresAt <= newStartsAt) return@transaction null
        val nextStatus = if (existing[SystemAnnouncements.status] == "ACTIVE") {
            // 已发布的公告不因改窗口而回退为草稿；窗口结束后自动 EXPIRED（读取侧判断）。
            "ACTIVE"
        } else if (now >= newStartsAt) {
            "ACTIVE"
        } else {
            "SCHEDULED"
        }
        // 8.34 修复 CAS：并发 cancel+update 此前可把 CANCELLED 公告复活成 ACTIVE/SCHEDULED
        //（publish/cancel 均有 CAS，唯独 update 没有）。WHERE 带状态条件，取消后更新即 no-op。
        val updated = SystemAnnouncements.update({
            (SystemAnnouncements.id eq id) and (SystemAnnouncements.status neq "CANCELLED")
        }) {
            title?.let { t -> it[SystemAnnouncements.title] = t.take(200) }
            content?.let { c -> it[SystemAnnouncements.content] = c }
            level?.let { l -> it[SystemAnnouncements.level] = l.uppercase().take(20) }
            targetAudience?.let { a -> it[SystemAnnouncements.targetAudience] = a.uppercase().take(20) }
            it[SystemAnnouncements.targetTagId] = targetTagId
            it[SystemAnnouncements.startsAt] = newStartsAt
            it[SystemAnnouncements.expiresAt] = newExpiresAt
            it[SystemAnnouncements.status] = nextStatus
            it[SystemAnnouncements.updatedAt] = now
            if (nextStatus == "ACTIVE" && existing[SystemAnnouncements.publishedAt] == null) {
                it[SystemAnnouncements.publishedAt] = now
            }
        }
        // 并发期间被取消 → UPDATE no-op，返回 null（路由按不存在处理）
        if (updated == 0) return@transaction null
        get(id)
    }

    /** 手动发布：DRAFT/SCHEDULED → ACTIVE，立即生效。 */
    fun publish(id: String, operatorId: String): AnnouncementRow? = transaction {
        val row = SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
            .firstOrNull() ?: return@transaction null
        val now = System.currentTimeMillis()
        // CAS：允许 DRAFT/SCHEDULED/ACTIVE（create 时 startsAt<=now 的公告直接是 ACTIVE，
        // 重复 publish 幂等成功）；CANCELLED 不在白名单 → 已取消的公告不会被重新发布。
        val updated = SystemAnnouncements.update({
            (SystemAnnouncements.id eq id) and
                (SystemAnnouncements.status inList listOf("DRAFT", "SCHEDULED", "ACTIVE"))
        }) {
            it[status] = "ACTIVE"
            it[startsAt] = now
            it[updatedAt] = now
            it[publishedAt] = now
        }
        if (updated == 0) return@transaction null
        get(id)
    }

    /** 取消公告：ACTIVE/SCHEDULED → CANCELLED（保留记录，用户端不再可见）。 */
    fun cancel(id: String, operatorId: String): AnnouncementRow? = transaction {
        val row = SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
            .firstOrNull() ?: return@transaction null
        val now = System.currentTimeMillis()
        // CAS：仅在 ACTIVE/SCHEDULED 时生效，防止并发下把刚发布的公告以外状态覆盖掉
        val updated = SystemAnnouncements.update({
            (SystemAnnouncements.id eq id) and
                (SystemAnnouncements.status inList listOf("ACTIVE", "SCHEDULED"))
        }) {
            it[status] = "CANCELLED"
            it[updatedAt] = now
            it[cancelledAt] = now
            it[cancelledBy] = operatorId
        }
        if (updated == 0) return@transaction null
        get(id)
    }

    /** 硬删除：仅限从未发布的 DRAFT；已发布公告一律走 cancel 保留审计痕迹。 */
    fun delete(id: String): Boolean = transaction {
        val row = SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
            .firstOrNull() ?: return@transaction false
        if (row[SystemAnnouncements.status] != "DRAFT") return@transaction false
        SystemAnnouncements.deleteWhere { SystemAnnouncements.id eq id }
        true
    }

    /** 公告统计：目标受众规模 + 发布信息（供管理后台展示）。 */
    fun stats(id: String): AnnouncementStats? = transaction {
        val row = SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
            .firstOrNull() ?: return@transaction null
        val audience = row[SystemAnnouncements.targetAudience]
        val tagId = row[SystemAnnouncements.targetTagId]
        val recipientCount = if (audience == "ALL") {
            Users.selectAll().where { Users.deletedAt.isNull() }.count()
        } else if (tagId != null) {
            UserTagAssignments.selectAll().where { UserTagAssignments.tagId eq tagId }.count()
        } else {
            0L
        }
        AnnouncementStats(
            recipientCount = recipientCount,
            audience = audience,
            targetTagId = tagId,
            createdAt = row[SystemAnnouncements.createdAt],
            publishedAt = row[SystemAnnouncements.publishedAt],
            cancelledAt = row[SystemAnnouncements.cancelledAt]
        )
    }

    data class AnnouncementStats(
        val recipientCount: Long,
        val audience: String,
        val targetTagId: String?,
        val createdAt: Long,
        val publishedAt: Long?,
        val cancelledAt: Long?
    )

    private fun ResultRow.toAnnouncementRow(): AnnouncementRow = AnnouncementRow(
        id = this[SystemAnnouncements.id],
        title = this[SystemAnnouncements.title],
        content = this[SystemAnnouncements.content],
        level = this[SystemAnnouncements.level],
        targetAudience = this[SystemAnnouncements.targetAudience],
        targetTagId = this[SystemAnnouncements.targetTagId],
        startsAt = this[SystemAnnouncements.startsAt],
        expiresAt = this[SystemAnnouncements.expiresAt],
        status = this[SystemAnnouncements.status],
        createdBy = this[SystemAnnouncements.createdBy],
        createdAt = this[SystemAnnouncements.createdAt],
        updatedAt = this[SystemAnnouncements.updatedAt],
        publishedAt = this[SystemAnnouncements.publishedAt],
        cancelledAt = this[SystemAnnouncements.cancelledAt]
    )
}
