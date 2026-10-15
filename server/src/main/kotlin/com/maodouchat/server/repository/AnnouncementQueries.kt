package com.maodouchat.server.repository

// 公告查询簇：单条/列表/用户可见/统计，原 AnnouncementRepository 成员按域拆出的顶层扩展函数。
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AnnouncementRepository.get(id: String, now: Long = System.currentTimeMillis()): AnnouncementRow? = transaction {
    SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
        .firstOrNull()?.let { it.toAnnouncementRow().withDerivedStatus(now) }
}

/** 管理端列表：按状态/关键词过滤，createdAt DESC 分页。 */
fun AnnouncementRepository.list(
    status: String? = null,
    query: String? = null,
    limit: Int = 50,
    offset: Long = 0,
    now: Long = System.currentTimeMillis()
): List<AnnouncementRow> = transaction {
    var base = SystemAnnouncements.selectAll()
    status?.takeIf { it.isNotBlank() }?.let { raw ->
        val st = raw.uppercase().take(20)
        // EXPIRED 不落库：ACTIVE 且窗口已过，读取侧派生。管理端筛 EXPIRED/ACTIVE 必须分开。
        base = when (st) {
            "EXPIRED" -> base.andWhere {
                (SystemAnnouncements.status eq "ACTIVE") and (SystemAnnouncements.expiresAt less now)
            }
            "ACTIVE" -> base.andWhere {
                (SystemAnnouncements.status eq "ACTIVE") and (SystemAnnouncements.expiresAt greaterEq now)
            }
            else -> base.andWhere { SystemAnnouncements.status eq st }
        }
    }
    query?.takeIf { it.isNotBlank() }?.let { q ->
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        base = base.andWhere {
            (SystemAnnouncements.title like "%$escaped%") or (SystemAnnouncements.content like "%$escaped%")
        }
    }
    base.orderBy(SystemAnnouncements.createdAt to SortOrder.DESC, SystemAnnouncements.id to SortOrder.DESC)
        .limit(limit, offset)
        .map { it.toAnnouncementRow().withDerivedStatus(now) }
}

fun AnnouncementRepository.activeForUser(userId: String, now: Long, userTagIds: List<String>): List<AnnouncementRow> = transaction {
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

/** 公告统计：目标受众规模 + 发布信息（供管理后台展示）。 */
fun AnnouncementRepository.stats(id: String): AnnouncementStats? = transaction {
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

private fun AnnouncementRow.withDerivedStatus(now: Long): AnnouncementRow =
    if (status == "ACTIVE" && expiresAt in 1 until now) copy(status = "EXPIRED") else this

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
