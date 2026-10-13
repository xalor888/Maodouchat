package com.maodouchat.server.repository

// Admin 身份导出：推送 token、会话统计、在线状态、隐私开关、受限用户（原 AdminExportIdentity 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 只导出元数据：token 只含前缀，绝不导出 tokenHash。 */
fun AdminExportRepository.pushTokens(limit: Int): List<List<Any?>> = transaction {
    PushTokens.selectAll()
        .orderBy(PushTokens.updatedAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
            val tok = row[PushTokens.token]
            val prefix = if (tok.length <= 12) tok.take(4) + "…" else tok.take(8) + "…" + tok.takeLast(4)
            listOf(
                row[PushTokens.userId],
                row[PushTokens.deviceId],
                row[PushTokens.platform],
                prefix,
                row[PushTokens.timezoneOffsetMinutes].toString(),
                row[PushTokens.updatedAt].toString(),
            )
        }
}

/** 隐私安全：每用户的活跃会话数，不含 token 秘密。 */
fun AdminExportRepository.sessionsSummary(limit: Int): List<List<Any?>> = transaction {
    // Aggregate active refresh sessions by userId if table exposes userId
    try {
        // Fall back to listing users with online flag only when refresh table schema is private
        val users = Users.selectAll()
            .orderBy(Users.lastSeen to SortOrder.DESC)
            .limit(limit)
            .toList()
        // 8.48 修复 M7：批量统计活跃会话（此前逐用户 count → N+1）
        val activeByUser = authTokenRepo.countActiveRefreshSessionsBatch(users.map { it[Users.id] })
        users.map { row ->
            val uid = row[Users.id]
            listOf(
                uid,
                row[Users.name].take(40),
                row[Users.isOnline].toString(),
                (activeByUser[uid] ?: 0).toString(),
                row[Users.lastSeen].toString(),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun AdminExportRepository.onlinePresence(limit: Int): List<List<Any?>> = transaction {
    Users.selectAll()
        .limit(limit)
        .map { row ->
            listOf(
                row[Users.id],
                row[Users.isOnline].toString(),
                row[Users.lastSeen].toString(),
                row[Users.showOnline].toString(),
            )
        }
}

fun AdminExportRepository.privacyFlags(limit: Int): List<List<Any?>> = transaction {
    Users.selectAll()
        .limit(limit)
        .map { row ->
            listOf(
                row[Users.id],
                row[Users.showOnline].toString(),
                row[Users.showStatus].toString(),
                row[Users.searchable].toString(),
            )
        }
}

fun AdminExportRepository.restrictedUsers(limit: Int): List<List<Any?>> {
    val now = System.currentTimeMillis()
    return transaction {
        Users.selectAll()
            .where {
                (Users.messageRestrictedUntil greater now) or
                    (Users.postRestrictedUntil greater now) or
                    (Users.suspendedUntil greater now)
            }
            .limit(limit)
            .map { row ->
                listOf(
                    row[Users.id],
                    row[Users.messageRestrictedUntil].toString(),
                    row[Users.postRestrictedUntil].toString(),
                    row[Users.suspendedUntil].toString(),
                )
            }
    }
}
