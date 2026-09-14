package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * M2：管理后台 CSV 导出的**唯一 SQL/Exposed 边界**。
 *
 * 此前 `AdminExportsRouting.kt` 在 26 个 handler 里各自 `transaction { … }` 直写 Exposed DSL，
 * 既破坏「repository=SQL 边界」契约，也让 27 个导出的查询逻辑无法被单测覆盖。
 * 这里只负责「取数」：每个方法返回原始单元格（`Any?`），
 * **不做 CSV 编码**——转义与公式注入防护属于 [com.maodouchat.server.service.AdminExportService]。
 *
 * 迁移原则：逐字搬运，行为不变；本类不引入任何新的过滤/排序/截断语义。
 */
class AdminExportRepository {

    /** 只导出元数据：token 只含前缀，绝不导出 tokenHash。 */
    fun pushTokens(limit: Int): List<List<Any?>> = transaction {
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

    fun users(limit: Int): List<List<Any?>> = transaction {
        Users.selectAll()
            .orderBy(Users.lastSeen to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                listOf(
                    row[Users.id],
                    row[Users.name],
                    row[Users.email],
                    row[Users.status],
                    row[Users.isOnline].toString(),
                    row[Users.isModerator].toString(),
                    row[Users.suspendedUntil].toString(),
                    row[Users.lastSeen].toString(),
                )
            }
    }

    /** 只导出元数据：token 只含前缀，绝不导出 tokenHash。 */
    fun bots(limit: Int): List<List<Any?>> = transaction {
        BotApps.selectAll()
            .orderBy(BotApps.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                listOf(
                    row[BotApps.id],
                    row[BotApps.name],
                    row[BotApps.username],
                    row[BotApps.ownerUserId],
                    row[BotApps.tokenPrefix],
                    row[BotApps.webhookUrl] ?: "",
                    row[BotApps.enabled].toString(),
                    row[BotApps.createdAt].toString(),
                    row[BotApps.updatedAt].toString(),
                )
            }
    }

    /** 审计元数据 only — no message bodies。 */
    fun moderationAudit(limit: Int): List<List<Any?>> = transaction {
        ModerationAuditLog.selectAll()
            .orderBy(
                ModerationAuditLog.createdAt to SortOrder.DESC,
                ModerationAuditLog.id to SortOrder.DESC,
            )
            .limit(limit)
            .map { row ->
                listOf(
                    row[ModerationAuditLog.id],
                    row[ModerationAuditLog.actorId].orEmpty(),
                    row[ModerationAuditLog.userId].orEmpty(),
                    row[ModerationAuditLog.action].take(40),
                    row[ModerationAuditLog.detail].orEmpty().replace("\n", " ").take(160),
                    row[ModerationAuditLog.createdAt].toString(),
                )
            }
    }

    /** 只有命令名 — no message bodies。 */
    fun botCommandStats(limit: Int): List<List<Any?>> = transaction {
        BotCommandLogs.selectAll()
            .orderBy(
                BotCommandLogs.createdAt to SortOrder.DESC,
                BotCommandLogs.id to SortOrder.DESC,
            )
            .limit(limit)
            .map { row ->
                listOf(
                    row[BotCommandLogs.id],
                    row[BotCommandLogs.botId],
                    (row[BotCommandLogs.chatId] ?: "").take(40),
                    (row[BotCommandLogs.userId] ?: "").take(40),
                    row[BotCommandLogs.command].take(80),
                    row[BotCommandLogs.createdAt].toString(),
                )
            }
    }

    /** 好友关系图元数据 only — no message bodies。 */
    fun friendships(limit: Int): List<List<Any?>> = transaction {
        Friendships.selectAll()
            .orderBy(
                Friendships.createdAt to SortOrder.DESC,
                Friendships.userLowId to SortOrder.DESC,
                Friendships.userHighId to SortOrder.DESC,
            )
            .limit(limit)
            .map { row ->
                listOf(
                    row[Friendships.userLowId],
                    row[Friendships.userHighId],
                    row[Friendships.createdAt].toString(),
                )
            }
    }

    /** 只有拉黑边 — no message bodies。 */
    fun blockedUsers(limit: Int): List<List<Any?>> = transaction {
        BlockedUsers.selectAll()
            .limit(limit)
            .map { row ->
                listOf(
                    row[BlockedUsers.blockerId],
                    row[BlockedUsers.blockedId],
                )
            }
    }
}
