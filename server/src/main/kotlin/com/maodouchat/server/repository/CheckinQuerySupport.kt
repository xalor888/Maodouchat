package com.maodouchat.server.repository

// 群签到查询 helpers 簇：DTO 组装与可见性谓词（原 CheckinCore 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.GroupCheckins
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

internal fun GroupCheckinRepository.toCheckinDto(
    chatId: String,
    userId: String,
    row: ResultRow,
    now: Long,
    viewerId: String = userId
): CheckinDto {
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
    return CheckinDto(
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

internal fun GroupCheckinRepository.visibleTodayPredicate(chatId: String, date: String, blocked: Set<String>) =
    if (blocked.isEmpty()) {
        (GroupCheckins.chatId eq chatId) and (GroupCheckins.checkinDate eq date)
    } else {
        (GroupCheckins.chatId eq chatId) and
            (GroupCheckins.checkinDate eq date) and
            (GroupCheckins.userId notInList blocked.toList())
    }


internal fun GroupCheckinRepository.visibleTodayCount(chatId: String, date: String, viewerId: String): Int {
    val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
    return GroupCheckins.selectAll()
        .where { visibleTodayPredicate(chatId, date, blocked) }
        .count().toInt()
}


internal fun GroupCheckinRepository.isMemberInTransaction(chatId: String, userId: String): Boolean =
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0


internal fun GroupCheckinRepository.isValidId(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
