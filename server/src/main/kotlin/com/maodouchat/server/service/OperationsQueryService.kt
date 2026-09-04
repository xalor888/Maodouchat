package com.maodouchat.server.service

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.ModerationRules
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.dayBucketExpression
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.AdminAuditLogResponse
import com.maodouchat.server.model.AdminDashboardResponse
import com.maodouchat.server.model.AdminRankingResponse
import com.maodouchat.server.model.AdminRichTrendsResponse
import com.maodouchat.server.model.AdminStorageResponse
import com.maodouchat.server.model.AdminTrendsResponse
import com.maodouchat.server.model.OnlineUserAdminResponse
import com.maodouchat.server.model.RankingEntryResponse
import com.maodouchat.server.model.StorageBreakdownEntry
import com.maodouchat.server.model.SystemStatsResponse
import com.maodouchat.server.model.TrendPointResponse
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * B13：运营统计只读 query model——管理后台 /dashboard、/system-stats、/trends、/online、
 * /ranking、/storage、/rich-trends 与审计导出背后的数据库查询，统一从 AdminObservabilityRouting
 * 抽出，路由只负责鉴权与 HTTP 序列化，不再内嵌事务。
 */
object OperationsQueryService {

    private const val DAY_MS = 86_400_000L

    fun dashboard(now: Long = System.currentTimeMillis()): AdminDashboardResponse = transaction {
        val activeCutoff = now - DAY_MS
        AdminDashboardResponse(
            totalUsers = Users.selectAll().where { Users.deletedAt.isNull() }.count(),
            activeUsers24h = Users.selectAll().where {
                Users.deletedAt.isNull() and (Users.lastSeen greaterEq activeCutoff)
            }.count(),
            deactivatedUsers = Users.selectAll().where { Users.deletedAt.isNotNull() }.count(),
            totalPosts = Posts.selectAll().count(),
            totalReports = Reports.selectAll().count(),
            pendingReports = Reports.selectAll().where { Reports.status eq "OPEN" }.count(),
            activeModerationRules = ModerationRules.selectAll().where { ModerationRules.enabled eq true }.count()
        )
    }

    fun systemStats(
        uptimeMs: Long,
        jvmMaxMemoryBytes: Long,
        jvmUsedMemoryBytes: Long,
        activeThreads: Int,
    ): SystemStatsResponse = transaction {
        SystemStatsResponse(
            totalMessages = MessagingV2Messages.selectAll().where {
                MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
            }.count(),
            totalChats = Chats.selectAll().count(),
            totalGroups = Chats.selectAll().where { Chats.isGroup eq true }.count(),
            totalAttachments = EncryptedAttachments.selectAll().count(),
            // 8.48 修复 M12：SQL 聚合 sum（此前整表载入 JVM 求和，大表 OOM）
            attachmentStorageBytes = EncryptedAttachments.slice(EncryptedAttachments.cipherSize.sum())
                .selectAll()
                .firstOrNull()
                ?.get(EncryptedAttachments.cipherSize.sum()) ?: 0L,
            totalPushTokens = PushTokens.selectAll().count(),
            totalAiCalls = AiAuditLogs.selectAll().count(),
            aiErrorCount = AiAuditLogs.selectAll().where {
                AiAuditLogs.status notInList listOf("SUCCESS", "OK", "success", "ok")
            }.count(),
            totalRiskEvents = RiskEvents.selectAll().count(),
            pendingRiskEvents = RiskEvents.selectAll().where { RiskEvents.needsReview eq true }.count(),
            totalComments = PostComments.selectAll().count(),
            totalPostLikes = PostLikes.selectAll().count(),
            serverUptimeMs = uptimeMs,
            jvmMaxMemoryBytes = jvmMaxMemoryBytes,
            jvmUsedMemoryBytes = jvmUsedMemoryBytes,
            activeThreads = activeThreads,
            onlineUsers = Users.selectAll().where {
                Users.deletedAt.isNull() and (Users.isOnline eq true)
            }.count()
        )
    }

    fun trends(now: Long = System.currentTimeMillis(), points: Int = 7): AdminTrendsResponse = transaction {
        val startMs = now - (points - 1) * DAY_MS
        val dayStartMs = startMs - (startMs % DAY_MS)
        val userBucket = dayBucketExpression(Users.lastSeen)
        val userCounts = Users
            .slice(userBucket, Users.id.count())
            .selectAll()
            .where { Users.lastSeen greaterEq dayStartMs }
            .groupBy(userBucket)
            .toList()
            .associate { it[userBucket] to it[Users.id.count()].toLong() }
        val msgBucket = dayBucketExpression(MessagingV2Messages.serverTimestamp)
        val messageCounts = MessagingV2Messages
            .slice(msgBucket, MessagingV2Messages.id.count())
            .selectAll()
            .where {
                (MessagingV2Messages.serverTimestamp greaterEq dayStartMs) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .groupBy(msgBucket)
            .toList()
            .associate { it[msgBucket] to it[MessagingV2Messages.id.count()].toLong() }
        val postBucket = dayBucketExpression(Posts.createdAt)
        val postCounts = Posts
            .slice(postBucket, Posts.id.count())
            .selectAll()
            .where { Posts.createdAt greaterEq dayStartMs }
            .groupBy(postBucket)
            .toList()
            .associate { it[postBucket] to it[Posts.id.count()].toLong() }
        val userPoints = mutableListOf<TrendPointResponse>()
        val messagePoints = mutableListOf<TrendPointResponse>()
        val postPoints = mutableListOf<TrendPointResponse>()
        for (i in points - 1 downTo 0) {
            val dayStart = now - i * DAY_MS
            val dayStartNorm = dayStart - (dayStart % DAY_MS)
            val bucket = dayStartNorm / DAY_MS
            userPoints += TrendPointResponse(dayStartNorm, userCounts[bucket] ?: 0)
            messagePoints += TrendPointResponse(dayStartNorm, messageCounts[bucket] ?: 0)
            postPoints += TrendPointResponse(dayStartNorm, postCounts[bucket] ?: 0)
        }
        AdminTrendsResponse(userPoints, messagePoints, postPoints)
    }

    fun onlineUsers(limit: Int): List<OnlineUserAdminResponse> = transaction {
        Users.selectAll().where {
            Users.deletedAt.isNull() and (Users.isOnline eq true)
        }.orderBy(Users.lastSeen to SortOrder.DESC).limit(limit).map {
            OnlineUserAdminResponse(
                id = it[Users.id],
                name = it[Users.name],
                email = it[Users.email],
                avatar = it[Users.avatar],
                lastSeen = it[Users.lastSeen],
                isModerator = it[Users.isModerator]
            )
        }
    }

    fun ranking(topN: Int): AdminRankingResponse = transaction {
        val msgCount = MessagingV2Messages.senderUserId.count()
        val topMessagers = (MessagingV2Messages innerJoin Users)
            .slice(MessagingV2Messages.senderUserId, Users.name, Users.avatar, msgCount)
            .selectAll()
            .where {
                Users.deletedAt.isNull() and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .groupBy(MessagingV2Messages.senderUserId, Users.name, Users.avatar)
            .orderBy(msgCount to SortOrder.DESC)
            .limit(topN)
            .map {
                RankingEntryResponse(
                    userId = it[MessagingV2Messages.senderUserId],
                    userName = it[Users.name],
                    avatar = it[Users.avatar],
                    value = it[msgCount]
                )
            }
        val postCount = Posts.authorId.count()
        val topPosters = (Posts innerJoin Users)
            .slice(Posts.authorId, Users.name, Users.avatar, postCount)
            .selectAll()
            .where { Users.deletedAt.isNull() and (Posts.status eq "PUBLISHED") }
            .groupBy(Posts.authorId, Users.name, Users.avatar)
            .orderBy(postCount to SortOrder.DESC)
            .limit(topN)
            .map {
                RankingEntryResponse(
                    userId = it[Posts.authorId],
                    userName = it[Users.name],
                    avatar = it[Users.avatar],
                    value = it[postCount]
                )
            }
        val storageSum = EncryptedAttachments.cipherSize.sum()
        val topStorageUsers = (EncryptedAttachments innerJoin Users)
            .slice(EncryptedAttachments.uploaderId, Users.name, Users.avatar, storageSum)
            .selectAll()
            .where { Users.deletedAt.isNull() and (EncryptedAttachments.status eq "COMMITTED") }
            .groupBy(EncryptedAttachments.uploaderId, Users.name, Users.avatar)
            .orderBy(storageSum to SortOrder.DESC)
            .limit(topN)
            .map {
                RankingEntryResponse(
                    userId = it[EncryptedAttachments.uploaderId],
                    userName = it[Users.name],
                    avatar = it[Users.avatar],
                    value = it[storageSum] ?: 0L,
                    detail = "bytes"
                )
            }
        val grpMsgCount = MessagingV2Messages.conversationId.count()
        val mostActiveGroups = (MessagingV2Messages innerJoin Chats)
            .slice(MessagingV2Messages.conversationId, Chats.groupName, grpMsgCount)
            .selectAll()
            .where {
                (Chats.isGroup eq true) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .groupBy(MessagingV2Messages.conversationId, Chats.groupName)
            .orderBy(grpMsgCount to SortOrder.DESC)
            .limit(topN)
            .map {
                RankingEntryResponse(
                    userId = it[MessagingV2Messages.conversationId],
                    userName = it[Chats.groupName] ?: it[MessagingV2Messages.conversationId],
                    avatar = null,
                    value = it[grpMsgCount]
                )
            }
        AdminRankingResponse(topMessagers, topPosters, topStorageUsers, mostActiveGroups)
    }

    fun storage(quotaBytes: Long): AdminStorageResponse = transaction {
        val allAttachments = EncryptedAttachments.selectAll()
            .where { EncryptedAttachments.status eq "COMMITTED" }
            .toList()
        val totalBytes = allAttachments.sumOf { it[EncryptedAttachments.cipherSize] }
        val totalFiles = allAttachments.size.toLong()
        val byMime = allAttachments.groupBy { att ->
            if (att[EncryptedAttachments.messageId] == null) "orphan" else "encrypted"
        }.map { (category, list) ->
            StorageBreakdownEntry(
                category = category,
                fileCount = list.size.toLong(),
                totalBytes = list.sumOf { it[EncryptedAttachments.cipherSize] }
            )
        }.sortedByDescending { it.totalBytes }
        val perUserBytes = allAttachments.groupBy { it[EncryptedAttachments.uploaderId] }
            .mapValues { it.value.sumOf { att -> att[EncryptedAttachments.cipherSize] } }
        val usersNearQuota = perUserBytes.count { it.value >= quotaBytes * 0.8 }
        AdminStorageResponse(
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            byCategory = byMime,
            quotaPerUserBytes = quotaBytes,
            usersNearQuota = usersNearQuota.toLong()
        )
    }

    fun richTrends(now: Long = System.currentTimeMillis(), points: Int = 7): AdminRichTrendsResponse = transaction {
        val startMs = now - (points - 1) * DAY_MS
        val dayStartMs = startMs - (startMs % DAY_MS)
        fun dayCounts(col: Column<Long>): Map<Long, Long> {
            val bucket = dayBucketExpression(col)
            return col.table.slice(bucket, col.count()).selectAll()
                .where { col greaterEq dayStartMs }
                .groupBy(bucket)
                .toList()
                .associate { it[bucket] to it[col.count()].toLong() }
        }
        val userCounts = dayCounts(Users.lastSeen)
        val messageBucket = dayBucketExpression(MessagingV2Messages.serverTimestamp)
        val messageCounts = MessagingV2Messages
            .slice(messageBucket, MessagingV2Messages.id.count())
            .selectAll()
            .where {
                (MessagingV2Messages.serverTimestamp greaterEq dayStartMs) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .groupBy(messageBucket)
            .associate { it[messageBucket] to it[MessagingV2Messages.id.count()].toLong() }
        val postCounts = dayCounts(Posts.createdAt)
        val reportCounts = dayCounts(Reports.createdAt)
        val aiCounts = dayCounts(AiAuditLogs.createdAt)
        val attachCounts = dayCounts(EncryptedAttachments.createdAt)
        val activeBucket = dayBucketExpression(Users.lastSeen)
        val activeDaily = Users
            .slice(activeBucket, Users.id.count())
            .selectAll()
            .where { Users.lastSeen greaterEq dayStartMs }
            .groupBy(activeBucket)
            .toList()
            .associate { it[activeBucket] to it[Users.id.count()].toLong() }
        var running = 0L
        val activeByDay = (0 until points).associate { i ->
            val dayStart = now - i * DAY_MS
            val dayStartNorm = dayStart - (dayStart % DAY_MS)
            running += activeDaily[dayStartNorm / DAY_MS] ?: 0
            dayStartNorm to running
        }
        val userPts = mutableListOf<TrendPointResponse>()
        val msgPts = mutableListOf<TrendPointResponse>()
        val postPts = mutableListOf<TrendPointResponse>()
        val reportPts = mutableListOf<TrendPointResponse>()
        val aiPts = mutableListOf<TrendPointResponse>()
        val attachPts = mutableListOf<TrendPointResponse>()
        val activePts = mutableListOf<TrendPointResponse>()
        for (i in points - 1 downTo 0) {
            val dayStart = now - i * DAY_MS
            val dayStartNorm = dayStart - (dayStart % DAY_MS)
            val bucket = dayStartNorm / DAY_MS
            userPts += TrendPointResponse(dayStartNorm, userCounts[bucket] ?: 0)
            msgPts += TrendPointResponse(dayStartNorm, messageCounts[bucket] ?: 0)
            postPts += TrendPointResponse(dayStartNorm, postCounts[bucket] ?: 0)
            reportPts += TrendPointResponse(dayStartNorm, reportCounts[bucket] ?: 0)
            aiPts += TrendPointResponse(dayStartNorm, aiCounts[bucket] ?: 0)
            attachPts += TrendPointResponse(dayStartNorm, attachCounts[bucket] ?: 0)
            activePts += TrendPointResponse(dayStartNorm, activeByDay[dayStartNorm] ?: 0)
        }
        AdminRichTrendsResponse(userPts, msgPts, postPts, reportPts, aiPts, attachPts, activePts)
    }

    fun auditLogs(limit: Int, offset: Long, action: String?, query: String?): List<AdminAuditLogResponse> =
        transaction {
            val base = ModerationAuditLog.selectAll()
            if (action != null) {
                base.andWhere { ModerationAuditLog.action eq action }
            }
            if (query != null) {
                val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                val pattern = "%${escaped.lowercase()}%"
                base.andWhere {
                    (ModerationAuditLog.actorId.lowerCase() like pattern) or
                        (ModerationAuditLog.userId.lowerCase() like pattern) or
                        (ModerationAuditLog.detail.lowerCase() like pattern) or
                        (ModerationAuditLog.action.lowerCase() like pattern)
                }
            }
            base.orderBy(ModerationAuditLog.createdAt to SortOrder.DESC, ModerationAuditLog.id to SortOrder.DESC)
                .limit(limit, offset)
                .map { it.toAuditLogResponse() }
        }

    fun auditLogsExport(limit: Int, offset: Int): List<AdminAuditLogResponse> = transaction {
        ModerationAuditLog.selectAll()
            .orderBy(ModerationAuditLog.createdAt to SortOrder.DESC, ModerationAuditLog.id to SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map { it.toAuditLogResponse() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toAuditLogResponse() = AdminAuditLogResponse(
        id = this[ModerationAuditLog.id],
        actorId = this[ModerationAuditLog.actorId],
        targetUserId = this[ModerationAuditLog.userId],
        action = this[ModerationAuditLog.action],
        detail = this[ModerationAuditLog.detail],
        createdAt = this[ModerationAuditLog.createdAt]
    )
}
