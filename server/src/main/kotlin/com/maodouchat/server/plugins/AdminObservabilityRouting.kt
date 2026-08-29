package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.lang.management.ManagementFactory
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction

/** Admin health, analytics, ranking, storage, and audit endpoints. */
internal fun Route.configureAdminObservabilityRoutes(serverConfig: ServerConfig) {
            get("/channel-health") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                call.respond(
                    AdminChannelHealthResponse(
                        openaiConfigured = serverConfig.openaiConfigured(),
                        turnConfigured = serverConfig.turnConfigured(),
                        smtpConfigured = serverConfig.smtpConfigured(),
                        jwtConfigured = serverConfig.jwtConfigured(),
                        openaiModel = serverConfig.openAiModel,
                        turnUrlCount = serverConfig.turnUrls.size,
                        smtpHostMasked = serverConfig.maskHost(serverConfig.smtpHost)
                    )
                )
            }

            // ─── 仪表盘概览 ───────────────────
            get("/dashboard") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val stats = transaction {
                    val activeCutoff = System.currentTimeMillis() - 86_400_000L
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
                call.respond(stats)
            }

            // ─── 系统健康统计 ─────────────────
            get("/system-stats") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val rt = ManagementFactory.getRuntimeMXBean()
                val mem = Runtime.getRuntime()
                val stats = transaction {
                    SystemStatsResponse(
                        totalMessages = MessagingV2Messages.selectAll().where {
                            MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE
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
                            AiAuditLogs.status notInList listOf(
                                "SUCCESS", "OK", "success", "ok"
                            )
                        }.count(),
                        totalRiskEvents = RiskEvents.selectAll().count(),
                        pendingRiskEvents = RiskEvents.selectAll().where { RiskEvents.needsReview eq true }.count(),
                        totalComments = PostComments.selectAll().count(),
                        totalPostLikes = PostLikes.selectAll().count(),
                        serverUptimeMs = System.currentTimeMillis() - rt.startTime,
                        jvmMaxMemoryBytes = mem.maxMemory(),
                        jvmUsedMemoryBytes = mem.totalMemory() - mem.freeMemory(),
                        activeThreads = Thread.activeCount(),
                        onlineUsers = Users.selectAll().where {
                            Users.deletedAt.isNull() and (Users.isOnline eq true)
                        }.count()
                    )
                }
                call.respond(stats)
            }

            // ─── 趋势数据（近7天） ────────────
            get("/trends") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val now = System.currentTimeMillis()
                val dayMs = 86_400_000L
                val points = 7
                val trends = transaction {
                    // 8.48 修复 M1：按天 GROUP BY 聚合（此前 7 天 × 3 表 = 21 次 count）
                    val startMs = now - (points - 1) * dayMs
                    val dayStartMs = startMs - (startMs % dayMs)
                    val userBucket = observabilityDayBucketExpression(Users.lastSeen)
                    val userCounts = Users
                        .slice(userBucket, Users.id.count())
                        .selectAll()
                        .where { Users.lastSeen greaterEq dayStartMs }
                        .groupBy(userBucket)
                        .toList()
                        .associate { it[userBucket] to it[Users.id.count()].toLong() }
                    val msgBucket = observabilityDayBucketExpression(MessagingV2Messages.serverTimestamp)
                    val messageCounts = MessagingV2Messages
                        .slice(msgBucket, MessagingV2Messages.id.count())
                        .selectAll()
                        .where {
                            (MessagingV2Messages.serverTimestamp greaterEq dayStartMs) and
                                (MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE)
                        }
                        .groupBy(msgBucket)
                        .toList()
                        .associate { it[msgBucket] to it[MessagingV2Messages.id.count()].toLong() }
                    val postBucket = observabilityDayBucketExpression(Posts.createdAt)
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
                        val dayStart = now - i * dayMs
                        val dayStartNorm = dayStart - (dayStart % dayMs)
                        val bucket = dayStartNorm / dayMs
                        userPoints += TrendPointResponse(dayStartNorm, userCounts[bucket] ?: 0)
                        messagePoints += TrendPointResponse(dayStartNorm, messageCounts[bucket] ?: 0)
                        postPoints += TrendPointResponse(dayStartNorm, postCounts[bucket] ?: 0)
                    }
                    AdminTrendsResponse(userPoints, messagePoints, postPoints)
                }
                call.respond(trends)
            }

            // ─── 在线用户列表 ─────────────────
            get("/online") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                val online = transaction {
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
                call.respond(online)
            }

            // ─── 活跃排行榜 ───────────────────
            get("/ranking") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val topN = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val ranking = transaction {
                    val msgCount = MessagingV2Messages.senderUserId.count()
                    val topMessagers = (MessagingV2Messages innerJoin Users)
                        .slice(MessagingV2Messages.senderUserId, Users.name, Users.avatar, msgCount)
                        .selectAll()
                        .where {
                            Users.deletedAt.isNull() and
                                (MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE)
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
                                (MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE)
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
                call.respond(ranking)
            }

            // ─── 存储用量明细 ─────────────────
            get("/storage") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val storage = transaction {
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
                    val quotaPerUser = serverConfig.userStorageQuotaBytes
                    val perUserBytes = allAttachments.groupBy { it[EncryptedAttachments.uploaderId] }
                        .mapValues { it.value.sumOf { att -> att[EncryptedAttachments.cipherSize] } }
                    val usersNearQuota = perUserBytes.count { it.value >= quotaPerUser * 0.8 }
                    AdminStorageResponse(
                        totalBytes = totalBytes,
                        totalFiles = totalFiles,
                        byCategory = byMime,
                        quotaPerUserBytes = quotaPerUser,
                        usersNearQuota = usersNearQuota.toLong()
                    )
                }
                call.respond(storage)
            }

            // ─── 丰富趋势（近7天，含举报/AI/附件/活跃） ──
            get("/rich-trends") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val now = System.currentTimeMillis()
                val dayMs = 86_400_000L
                val points = 7
                val trends = transaction {
                    // 8.48 修复 M2：按天 GROUP BY 聚合（此前 7 天 × 7 表 = 49 次 count）
                    val startMs = now - (points - 1) * dayMs
                    val dayStartMs = startMs - (startMs % dayMs)
                    fun dayCounts(col: Column<Long>): Map<Long, Long> {
                        val bucket = observabilityDayBucketExpression(col)
                        return col.table.slice(bucket, col.count()).selectAll()
                            .where { col greaterEq dayStartMs }
                            .groupBy(bucket)
                            .toList()
                            .associate { it[bucket] to it[col.count()].toLong() }
                    }
                    val userCounts = dayCounts(Users.lastSeen)
                    val messageBucket = observabilityDayBucketExpression(MessagingV2Messages.serverTimestamp)
                    val messageCounts = MessagingV2Messages
                        .slice(messageBucket, MessagingV2Messages.id.count())
                        .selectAll()
                        .where {
                            (MessagingV2Messages.serverTimestamp greaterEq dayStartMs) and
                                (MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE)
                        }
                        .groupBy(messageBucket)
                        .associate { it[messageBucket] to it[MessagingV2Messages.id.count()].toLong() }
                    val postCounts = dayCounts(Posts.createdAt)
                    val reportCounts = dayCounts(Reports.createdAt)
                    val aiCounts = dayCounts(AiAuditLogs.createdAt)
                    val attachCounts = dayCounts(EncryptedAttachments.createdAt)
                    val activeBucket = observabilityDayBucketExpression(Users.lastSeen)
                    val activeDaily = Users
                        .slice(activeBucket, Users.id.count())
                        .selectAll()
                        .where { Users.lastSeen greaterEq dayStartMs }
                        .groupBy(activeBucket)
                        .toList()
                        .associate { it[activeBucket] to it[Users.id.count()].toLong() }
                    var running = 0L
                    val activeByDay = (0 until points).associate { i ->
                        val dayStart = now - i * dayMs
                        val dayStartNorm = dayStart - (dayStart % dayMs)
                        running += activeDaily[dayStartNorm / dayMs] ?: 0
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
                        val dayStart = now - i * dayMs
                        val dayStartNorm = dayStart - (dayStart % dayMs)
                        val bucket = dayStartNorm / dayMs
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
                call.respond(trends)
            }

            // ─── 操作审计 ─────────────────────
            get("/audit-logs") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val actionFilter = call.request.queryParameters["action"]?.trim()?.takeIf { it.isNotBlank() }
                val q = call.request.queryParameters["q"]?.trim()?.take(80)?.takeIf { it.isNotBlank() }
                val logs = transaction {
                    val query = ModerationAuditLog.selectAll()
                    if (actionFilter != null) {
                        query.andWhere { ModerationAuditLog.action eq actionFilter }
                    }
                    if (q != null) {
                        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                        val pattern = "%${escaped.lowercase()}%"
                        query.andWhere {
                            (ModerationAuditLog.actorId.lowerCase() like pattern) or
                                (ModerationAuditLog.userId.lowerCase() like pattern) or
                                (ModerationAuditLog.detail.lowerCase() like pattern) or
                                (ModerationAuditLog.action.lowerCase() like pattern)
                        }
                    }
                    query.orderBy(ModerationAuditLog.createdAt to SortOrder.DESC, ModerationAuditLog.id to SortOrder.DESC)
                        .limit(limit, offset)
                        .map {
                            AdminAuditLogResponse(
                                id = it[ModerationAuditLog.id],
                                actorId = it[ModerationAuditLog.actorId],
                                targetUserId = it[ModerationAuditLog.userId],
                                action = it[ModerationAuditLog.action],
                                detail = it[ModerationAuditLog.detail],
                                createdAt = it[ModerationAuditLog.createdAt]
                            )
                        }
                }
                call.respond(logs)
            }

            get("/audit-logs/export") {
                if (!call.isAdminObservabilityUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5_000).coerceIn(1, 10_000)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val logs = transaction {
                    ModerationAuditLog.selectAll()
                        .orderBy(ModerationAuditLog.createdAt to SortOrder.DESC, ModerationAuditLog.id to SortOrder.DESC)
                        .limit(limit, offset.toLong())
                        .map {
                            AdminAuditLogResponse(
                                id = it[ModerationAuditLog.id],
                                actorId = it[ModerationAuditLog.actorId],
                                targetUserId = it[ModerationAuditLog.userId],
                                action = it[ModerationAuditLog.action],
                                detail = it[ModerationAuditLog.detail],
                                createdAt = it[ModerationAuditLog.createdAt]
                            )
                        }
                }
                recordObservabilityAudit(actorId, "ADMIN_AUDIT_EXPORTED", "count=${logs.size}")
                val csv = buildString {
                    append('\uFEFF').append("id,actorId,targetUserId,action,detail,createdAt\r\n")
                    logs.forEach { log ->
                        append(observabilityCsvCell(log.id)).append(',')
                        append(observabilityCsvCell(log.actorId)).append(',')
                        append(observabilityCsvCell(log.targetUserId)).append(',')
                        append(observabilityCsvCell(log.action)).append(',')
                        append(observabilityCsvCell(log.detail)).append(',')
                        append(log.createdAt).append("\r\n")
                    }
                }
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-admin-audit-${System.currentTimeMillis()}.csv\""
                )
                call.respondText(csv, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
            }
}

private suspend fun ApplicationCall.isAdminObservabilityUser(): Boolean {
    val principal = principal<JWTPrincipal>() ?: return false
    return AdminAccess.isAdmin(principal.payload.subject)
}

private fun recordObservabilityAudit(actorId: String, action: String, detail: String) {
    transaction {
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = actorId
            it[ModerationAuditLog.action] = action.take(40)
            it[ModerationAuditLog.detail] = detail.take(500)
            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
        }
    }
}

private fun observabilityCsvCell(value: Any?): String {
    val text = value?.toString().orEmpty()
    return if (text.any { it == '"' || it == '\n' || it == '\r' || it == ',' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else text
}

private fun observabilityDayBucketExpression(column: Column<Long>): Expression<Long> =
    object : Expression<Long>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("CAST(")
            column.toQueryBuilder(queryBuilder)
            queryBuilder.append(" / 86400000 AS BIGINT)")
        }
    }
