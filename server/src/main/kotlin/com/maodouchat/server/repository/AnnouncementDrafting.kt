package com.maodouchat.server.repository

// 公告起草簇：创建与编辑草稿/调度公告（原 AnnouncementLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.SystemAnnouncements
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun AnnouncementRepository.create(
    title: String,
    content: String,
    level: String,
    targetAudience: String,
    targetTagId: String?,
    startsAt: Long,
    expiresAt: Long,
    createdBy: String?,
    asDraft: Boolean = false
): AnnouncementRow = transaction {
    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    // 草稿必须显式 asDraft：此前 create 只写 ACTIVE/SCHEDULED，管理端删除仅允许 DRAFT，
    // 导致「新建 → 删除」永远 409，发布按钮也永远对草稿不可见。
    val status = when {
        asDraft -> "DRAFT"
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

/** 更新草稿/调度字段；已发布（ACTIVE）公告仅允许延后/提前窗口。 */
fun AnnouncementRepository.update(
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
    val existingStatus = existing[SystemAnnouncements.status]
    val nextStatus = when {
        // 已发布的公告不因改窗口而回退为草稿；窗口结束后自动 EXPIRED（读取侧判断）。
        existingStatus == "ACTIVE" -> "ACTIVE"
        // 草稿必须走 publish，编辑标题/窗口不得悄悄变成 ACTIVE。
        existingStatus == "DRAFT" -> "DRAFT"
        now >= newStartsAt -> "ACTIVE"
        else -> "SCHEDULED"
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
