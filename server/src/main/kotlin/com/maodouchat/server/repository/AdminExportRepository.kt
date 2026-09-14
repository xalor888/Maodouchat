package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.count
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
class AdminExportRepository(
    private val authTokenRepo: AuthTokenRepository = AuthTokenRepository(),
) {

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

    fun reports(limit: Int): List<List<Any?>> = transaction {
        Reports.selectAll()
            .orderBy(
                Reports.createdAt to SortOrder.DESC,
                Reports.id to SortOrder.DESC,
            )
            .limit(limit)
            .map { row ->
                listOf(
                    row[Reports.id],
                    row[Reports.reporterId],
                    row[Reports.targetType],
                    row[Reports.targetId],
                    row[Reports.reason].take(200),
                    row[Reports.status],
                    row[Reports.createdAt].toString(),
                )
            }
    }

    fun riskEvents(limit: Int): List<List<Any?>> = transaction {
        RiskEvents.selectAll()
            .orderBy(
                RiskEvents.createdAt to SortOrder.DESC,
                RiskEvents.id to SortOrder.DESC,
            )
            .limit(limit)
            .map { row ->
                listOf(
                    row[RiskEvents.id],
                    row[RiskEvents.userId],
                    row[RiskEvents.sourceValue],
                    row[RiskEvents.action],
                    (row[RiskEvents.matched] ?: "").replace("\n", " ").take(200),
                    row[RiskEvents.needsReview].toString(),
                    row[RiskEvents.createdAt].toString(),
                )
            }
    }

    /** 隐私安全：每用户的活跃会话数，不含 token 秘密。 */
    fun sessionsSummary(limit: Int): List<List<Any?>> = transaction {
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

    /** 按 kind 聚合的持久化传输统计（只统计 MESSAGE 类，绝不导出消息体）。 */
    data class MessageStats(val byKind: List<List<Any?>>, val total: Long)

    fun messageStats(): MessageStats {
        val byKind = transaction {
            MessagingV2Messages
                .slice(MessagingV2Messages.kind, MessagingV2Messages.kind.count())
                .selectAll()
                .where {
                    MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
                }
                .groupBy(MessagingV2Messages.kind)
                .map { row ->
                    listOf(
                        row[MessagingV2Messages.kind],
                        row[MessagingV2Messages.kind.count()],
                    )
                }
        }
        val total = transaction {
            MessagingV2Messages.selectAll().where {
                MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
            }.count()
        }
        return MessageStats(byKind, total)
    }
}
