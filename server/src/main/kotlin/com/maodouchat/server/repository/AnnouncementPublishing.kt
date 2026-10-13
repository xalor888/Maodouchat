package com.maodouchat.server.repository

// 公告发布流转簇：手动发布与取消（原 AnnouncementLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.SystemAnnouncements
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 手动发布：DRAFT/SCHEDULED → ACTIVE，立即生效。 */
fun AnnouncementRepository.publish(id: String, operatorId: String): AnnouncementRepository.AnnouncementRow? = transaction {
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
fun AnnouncementRepository.cancel(id: String, operatorId: String): AnnouncementRepository.AnnouncementRow? = transaction {
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
