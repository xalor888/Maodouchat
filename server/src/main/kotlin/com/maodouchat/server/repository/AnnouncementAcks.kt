package com.maodouchat.server.repository

// 公告已读簇：ack 写入与已读统计，原 AnnouncementRepository 成员按域拆出的顶层扩展函数。
import com.maodouchat.server.db.AnnouncementAcks
import com.maodouchat.server.db.SystemAnnouncements
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** 某用户已读过的公告 id 集合（供 active 列表标记 `acked`）。 */
fun AnnouncementRepository.ackedAnnouncementIds(userId: String): Set<String> = transaction {
    AnnouncementAcks.selectAll().where { AnnouncementAcks.userId eq userId }
        .map { it[AnnouncementAcks.announcementId] }
        .toSet()
}

fun AnnouncementRepository.isAckVisibleToUser(id: String, userId: String, now: Long, userTagIds: Set<String>): Boolean = transaction {
    SystemAnnouncements.selectAll().where {
        (SystemAnnouncements.id eq id) and
            (SystemAnnouncements.status eq "ACTIVE") and
            (SystemAnnouncements.startsAt lessEq now) and
            (SystemAnnouncements.expiresAt greaterEq now)
    }.firstOrNull()?.let { row ->
        val audience = row[SystemAnnouncements.targetAudience]
        if (audience == "ALL") true else {
            val tagId = row[SystemAnnouncements.targetTagId]
            tagId != null && tagId in userTagIds
        }
    } ?: false
}

/** 写入一条已读（幂等 upsert，重复 ack 不产生第二行）。 */
fun AnnouncementRepository.markAcked(announcementId: String, userId: String, ackedAt: Long = System.currentTimeMillis()) {
    transaction {
        AnnouncementAcks.upsert(AnnouncementAcks.announcementId, AnnouncementAcks.userId) {
            it[AnnouncementAcks.announcementId] = announcementId
            it[AnnouncementAcks.userId] = userId
            it[AnnouncementAcks.ackedAt] = ackedAt
        }
    }
}

/** 某公告的已读人数（管理后台统计）。 */
fun AnnouncementRepository.ackedCount(announcementId: String): Long = transaction {
    AnnouncementAcks.selectAll().where { AnnouncementAcks.announcementId eq announcementId }.count()
}
