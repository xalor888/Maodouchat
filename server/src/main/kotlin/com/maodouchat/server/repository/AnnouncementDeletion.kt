package com.maodouchat.server.repository

// 公告删除簇：仅限从未发布草稿的硬删除（原 AnnouncementLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.SystemAnnouncements
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 硬删除：仅限从未发布的 DRAFT；已发布公告一律走 cancel 保留审计痕迹。 */
fun AnnouncementRepository.delete(id: String): Boolean = transaction {
    val row = SystemAnnouncements.selectAll().where { SystemAnnouncements.id eq id }
        .firstOrNull() ?: return@transaction false
    if (row[SystemAnnouncements.status] != "DRAFT") return@transaction false
    SystemAnnouncements.deleteWhere { SystemAnnouncements.id eq id }
    true
}
