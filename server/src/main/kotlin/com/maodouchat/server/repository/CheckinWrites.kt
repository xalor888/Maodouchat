package com.maodouchat.server.repository

// 群签到写入簇：签到与事务内写入（原 CheckinCore 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

// ── 群签到 ──────────────────────────────────────────

fun GroupCheckinRepository.checkIn(chatId: String, userId: String): CheckinDto? {
    if (!isValidId(chatId) || !isValidId(userId)) return null
    val today = LocalDate.now().toString()
    return try {
        checkInInTransaction(chatId, userId, today)
    } catch (error: Exception) {
        // 9.152：并发双签（双击/客户端重试）——两请求同时通过 existing==null 检查，
        // 后到者 INSERT 撞 (chatId,userId,checkinDate) 唯一约束。捕获必须在事务外
        //（PG 唯一冲突会 abort 整事务），回滚后新事务幂等回读当日已存在行。
        if (!isUniqueViolation(error)) throw error
        transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            val existing = GroupCheckins.selectAll().where {
                (GroupCheckins.chatId eq chatId) and
                    (GroupCheckins.userId eq userId) and
                    (GroupCheckins.checkinDate eq today)
            }.firstOrNull() ?: return@transaction null
            toCheckinDto(chatId, userId, existing, System.currentTimeMillis())
        }
    }
}


private fun GroupCheckinRepository.checkInInTransaction(chatId: String, userId: String, today: String): CheckinDto? = transaction {
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction null
    if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null

    val now = System.currentTimeMillis()
    val existing = GroupCheckins.selectAll().where {
        (GroupCheckins.chatId eq chatId) and
            (GroupCheckins.userId eq userId) and
            (GroupCheckins.checkinDate eq today)
    }.firstOrNull()

    val previous = GroupCheckins.selectAll().where {
        (GroupCheckins.chatId eq chatId) and (GroupCheckins.userId eq userId)
    }
        .orderBy(GroupCheckins.checkinDate to SortOrder.DESC)
        .limit(1)
        .firstOrNull()

    if (existing != null) {
        return@transaction toCheckinDto(chatId, userId, existing, now)
    }

    val yesterday = LocalDate.now().minusDays(1).toString()
    val streak = if (previous != null && previous[GroupCheckins.checkinDate] == yesterday) {
        (previous[GroupCheckins.streak] + 1).coerceAtLeast(1)
    } else 1
    val totalCount = (previous?.get(GroupCheckins.totalCount) ?: 0) + 1

    GroupCheckins.insert {
        it[GroupCheckins.chatId] = chatId
        it[GroupCheckins.userId] = userId
        it[GroupCheckins.checkinDate] = today
        it[GroupCheckins.streak] = streak
        it[GroupCheckins.totalCount] = totalCount
        it[GroupCheckins.checkedAt] = now
    }
    val row = GroupCheckins.selectAll().where {
        (GroupCheckins.chatId eq chatId) and
            (GroupCheckins.userId eq userId) and
            (GroupCheckins.checkinDate eq today)
    }.first()
    toCheckinDto(chatId, userId, row, now)
}
