package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

/** 群签到核心簇：签到 / 我的签到 / 他人视角 / 排行。 */

// ── 群签到 ──────────────────────────────────────────

fun GroupCheckinRepository.checkIn(chatId: String, userId: String): GroupCheckinRepository.CheckinDto? {
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

private fun GroupCheckinRepository.checkInInTransaction(chatId: String, userId: String, today: String): GroupCheckinRepository.CheckinDto? = transaction {
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

fun GroupCheckinRepository.myCheckin(chatId: String, userId: String): GroupCheckinRepository.CheckinDto? {
    if (!isValidId(chatId) || !isValidId(userId)) return null
    val today = LocalDate.now().toString()
    return transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction null
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
        val row = GroupCheckins.selectAll().where {
            (GroupCheckins.chatId eq chatId) and
                (GroupCheckins.userId eq userId) and
                (GroupCheckins.checkinDate eq today)
        }.firstOrNull()
        if (row == null) {
            // 未签到也返回一条友好快照（streak 取自最近一次）
            val previous = GroupCheckins.selectAll().where {
                (GroupCheckins.chatId eq chatId) and (GroupCheckins.userId eq userId)
            }
                .orderBy(GroupCheckins.checkinDate to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
            // 9.130：最近一次是昨天 → 连续天数尚未断，快照沿用昨日 streak（客户端直接渲染
            // streak 文案，此前恒 0 会让昨天刚签过的用户误以为断签）；更早则确已断签为 0
            val yesterday = LocalDate.now().minusDays(1).toString()
            val liveStreak = if (previous != null && previous[GroupCheckins.checkinDate] == yesterday) {
                previous[GroupCheckins.streak].coerceAtLeast(1)
            } else 0
            // 未签到时 todayRank 仍为 0，但 todayCount 必须是当日可见签到人数——
            // 客户端 refresh 用 /checkins/me 的 todayCount 渲染「今日 N 人」，
            // 此前恒 0 会把已有签到的群显示成空壳。
            return@transaction GroupCheckinRepository.CheckinDto(
                chatId = chatId,
                userId = userId,
                date = today,
                streak = liveStreak,
                totalCount = previous?.get(GroupCheckins.totalCount) ?: 0,
                todayRank = 0,
                todayCount = visibleTodayCount(chatId, today, userId),
                alreadyCheckedIn = false,
                checkedAt = 0L
            )
        }
        toCheckinDto(chatId, userId, row, System.currentTimeMillis())
    }
}

/** Actor's check-in DTO as seen by [viewerId]; null when the viewer is blocked from the actor. */
fun GroupCheckinRepository.checkinForViewer(chatId: String, userId: String, viewerId: String): GroupCheckinRepository.CheckinDto? {
    if (!isValidId(chatId) || !isValidId(userId) || !isValidId(viewerId)) return null
    val today = LocalDate.now().toString()
    return transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction null
        if (
            !chat[Chats.isGroup] ||
            !isMemberInTransaction(chatId, userId) ||
            !isMemberInTransaction(chatId, viewerId)
        ) return@transaction null
        if (userId in ConversationVisibility.blockedUserIdsInTx(viewerId)) return@transaction null
        val row = GroupCheckins.selectAll().where {
            (GroupCheckins.chatId eq chatId) and
                (GroupCheckins.userId eq userId) and
                (GroupCheckins.checkinDate eq today)
        }.firstOrNull() ?: return@transaction null
        toCheckinDto(chatId, userId, row, System.currentTimeMillis(), viewerId)
    }
}

fun GroupCheckinRepository.checkinRanking(chatId: String, limit: Int = 20, viewerId: String? = null): List<GroupCheckinRepository.CheckinRankEntry> {
    if (chatId.isBlank()) return emptyList()
    return transaction {
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        // 8.46 修复：原先把全群签到历史载入内存（500 人×365 天≈18 万行）+ groupBy；
        // 改为一条 ROW_NUMBER 窗口函数 SQL 只取每个用户「最新一行」的 streak/totalCount/checkedAt。
        val safeLimit = limit.coerceIn(1, 100)
        val blockedFilter = if (blocked.isEmpty()) "" else {
            " AND user_id NOT IN (" + List(blocked.size) { "?" }.joinToString(",") + ")"
        }
        val sql = """
            SELECT user_id, streak, total_count, checked_at FROM (
                SELECT user_id, streak, total_count, checked_at,
                       ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY checkin_date DESC) AS rn
                FROM group_checkins
                WHERE chat_id = ?
            ) t WHERE rn = 1
            $blockedFilter
            ORDER BY total_count DESC, streak DESC, checked_at DESC
            LIMIT ?
        """.trimIndent()
        val params = mutableListOf<Pair<org.jetbrains.exposed.sql.IColumnType, Any>>(
            org.jetbrains.exposed.sql.VarCharColumnType() to chatId
        )
        blocked.forEach { id ->
            params += org.jetbrains.exposed.sql.VarCharColumnType() to id
        }
        params += org.jetbrains.exposed.sql.IntegerColumnType() to safeLimit
        org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
            sql,
            params
        ) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        GroupCheckinRepository.CheckinRankEntry(
                            userId = rs.getString("user_id"),
                            streak = rs.getInt("streak"),
                            totalCount = rs.getInt("total_count"),
                            lastCheckedAt = rs.getLong("checked_at")
                        )
                    )
                }
            }
        } ?: emptyList()
    }
}

private fun GroupCheckinRepository.toCheckinDto(
    chatId: String,
    userId: String,
    row: ResultRow,
    now: Long,
    viewerId: String = userId
): GroupCheckinRepository.CheckinDto {
    val date = row[GroupCheckins.checkinDate]
    val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
    // 8.48 修复 M14：rank/count 用 COUNT 聚合——此前全量载入当日签到行（活跃大群上万行）
    val myCheckedAt = row[GroupCheckins.checkedAt]
    val visibleBase = visibleTodayPredicate(chatId, date, blocked)
    val todayCount = GroupCheckins.selectAll()
        .where { visibleBase }
        .count().toInt()
    // rank = 在我之前签到的行数 + 1（同 checkedAt 用 userId 稳定排序）
    val earlier = GroupCheckins.selectAll()
        .where {
            visibleBase and
                ((GroupCheckins.checkedAt less myCheckedAt) or
                    ((GroupCheckins.checkedAt eq myCheckedAt) and (GroupCheckins.userId less userId)))
        }
        .count().toInt()
    return GroupCheckinRepository.CheckinDto(
        chatId = chatId,
        userId = userId,
        date = date,
        streak = row[GroupCheckins.streak],
        totalCount = row[GroupCheckins.totalCount],
        todayRank = earlier + 1,
        todayCount = todayCount,
        alreadyCheckedIn = true,
        checkedAt = myCheckedAt
    )
}

/** 9.152：唯一约束冲突检测（与 StarMessageRepository 同口径）。 */

private fun GroupCheckinRepository.visibleTodayPredicate(chatId: String, date: String, blocked: Set<String>) =
    if (blocked.isEmpty()) {
        (GroupCheckins.chatId eq chatId) and (GroupCheckins.checkinDate eq date)
    } else {
        (GroupCheckins.chatId eq chatId) and
            (GroupCheckins.checkinDate eq date) and
            (GroupCheckins.userId notInList blocked.toList())
    }

private fun GroupCheckinRepository.visibleTodayCount(chatId: String, date: String, viewerId: String): Int {
    val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
    return GroupCheckins.selectAll()
        .where { visibleTodayPredicate(chatId, date, blocked) }
        .count().toInt()
}

private fun GroupCheckinRepository.isMemberInTransaction(chatId: String, userId: String): Boolean =
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0

private fun GroupCheckinRepository.isValidId(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
