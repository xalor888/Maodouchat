package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AdminDispositionPolicy
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.lang.management.ManagementFactory
import java.util.UUID

/**
 * 独立管理后台 API。仅允许 MASTER_ADMINS 中配置的账号访问；普通内容审核员继续使用受限审核 API。
 * Web 后台使用密码二次确认换取 5 分钟、带 admin_session 用途声明的专用 Token。
 */
fun Application.configureAdminRouting(
    userRepo: UserRepository,
    postRepo: PostRepository,
    chatRepo: ChatRepository,
    moderationRuleRepo: ModerationRuleRepository,
    reportRepo: ReportRepository = ReportRepository()
) {
    val authTokenRepo = AuthTokenRepository()
    routing {
        // Public shell only; no management data is embedded. Credentials are exchanged for a
        // dedicated short-lived admin token and the token stays in page memory (never localStorage).
        get("/admin") {
            call.respondAdminDashboardPage()
        }
        get("/admin/assets/admin.css") {
            call.respondAdminAsset(adminDashboardCss, io.ktor.http.ContentType.Text.CSS)
        }
        get("/admin/assets/admin-theme.js") {
            call.respondAdminAsset(adminDashboardThemeJs, io.ktor.http.ContentType.Application.JavaScript)
        }
        get("/admin/assets/admin-branding.js") {
            call.respondAdminAsset(adminDashboardBrandingJs, io.ktor.http.ContentType.Application.JavaScript)
        }
        get("/admin/assets/admin.js") {
            call.respondAdminAsset(adminDashboardJs, io.ktor.http.ContentType.Application.JavaScript)
        }
        // 双认证：普通 access token 用于首次换发 admin session；
        // admin session token 需进入 handler 走「不能续签自身」的 400 拒绝分支
        // （仅 auth-jwt 时 admin token 会被 requireAuthSession 校验拒为 401，该分支不可达）。
        authenticate("auth-jwt", "admin-jwt") {
            post("/api/admin/session") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.subject
                if (JwtConfig.isAdminSession(principal.payload)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("管理员会话不能续签自身"))
                }
                if (!AdminAccess.isAdmin(userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要主管理员权限"))
                }
                if (!adminSessionAttemptLimiter.acquire(userId)) {
                    call.response.headers.append(HttpHeaders.RetryAfter, ADMIN_SESSION_ATTEMPT_WINDOW_SECONDS.toString())
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("管理员二次验证尝试过多，请稍后再试"))
                }
                val request = call.receiveAdminJson<AdminSessionRequest>()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                if (!userRepo.verifyPassword(userId, request.password)) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("管理员密码错误"))
                }
                adminSessionAttemptLimiter.reset(userId)
                val issuedAt = System.currentTimeMillis()
                val token = JwtConfig.generateAdminToken(
                    userId = userId,
                    tokenVersion = authTokenRepo.getAccessTokenVersion(userId),
                    issuedAtMs = issuedAt
                )
                recordAdminAudit(userId, "ADMIN_SESSION_ISSUED", "expiresAt=${JwtConfig.adminTokenExpiresAt(issuedAt)}")
                call.respond(AdminSessionResponse(token, JwtConfig.adminTokenExpiresAt(issuedAt)))
            }
        }
        authenticate("admin-jwt") {
            route("/api/admin") {

            // ─── 仪表盘概览 ───────────────────
            get("/dashboard") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val rt = ManagementFactory.getRuntimeMXBean()
                val mem = Runtime.getRuntime()
                val stats = transaction {
                    SystemStatsResponse(
                        totalMessages = Messages.selectAll().count(),
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
                            AiAuditLogs.status notInList listOf("SUCCESS", "OK")
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val now = System.currentTimeMillis()
                val dayMs = 86_400_000L
                val points = 7
                val trends = transaction {
                    // 8.48 修复 M1：按天 GROUP BY 聚合（此前 7 天 × 3 表 = 21 次 count）
                    val startMs = now - (points - 1) * dayMs
                    val dayStartMs = startMs - (startMs % dayMs)
                    val userBucket = dayBucketExpression(Users.lastSeen)
                    val userCounts = Users
                        .slice(userBucket, Users.id.count())
                        .selectAll()
                        .where { Users.lastSeen greaterEq dayStartMs }
                        .groupBy(userBucket)
                        .toList()
                        .associate { it[userBucket] to it[Users.id.count()].toLong() }
                    val msgBucket = dayBucketExpression(Messages.timestamp)
                    val messageCounts = Messages
                        .slice(msgBucket, Messages.id.count())
                        .selectAll()
                        .where { Messages.timestamp greaterEq dayStartMs }
                        .groupBy(msgBucket)
                        .toList()
                        .associate { it[msgBucket] to it[Messages.id.count()].toLong() }
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val topN = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val ranking = transaction {
                    val msgCount = Messages.senderId.count()
                    val topMessagers = (Messages innerJoin Users)
                        .slice(Messages.senderId, Users.name, Users.avatar, msgCount)
                        .selectAll()
                        .where { Users.deletedAt.isNull() }
                        .groupBy(Messages.senderId, Users.name, Users.avatar)
                        .orderBy(msgCount to SortOrder.DESC)
                        .limit(topN)
                        .map {
                            RankingEntryResponse(
                                userId = it[Messages.senderId],
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
                    val grpMsgCount = Messages.chatId.count()
                    val mostActiveGroups = (Messages innerJoin Chats)
                        .slice(Messages.chatId, Chats.groupName, grpMsgCount)
                        .selectAll()
                        .where { Chats.isGroup eq true }
                        .groupBy(Messages.chatId, Chats.groupName)
                        .orderBy(grpMsgCount to SortOrder.DESC)
                        .limit(topN)
                        .map {
                            RankingEntryResponse(
                                userId = it[Messages.chatId],
                                userName = it[Chats.groupName] ?: it[Messages.chatId],
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val storage = transaction {
                    val allAttachments = EncryptedAttachments.selectAll()
                        .where { EncryptedAttachments.status eq "COMMITTED" }
                        .toList()
                    val totalBytes = allAttachments.sumOf { it[EncryptedAttachments.cipherSize] }
                    val totalFiles = allAttachments.size.toLong()
                    // 8.48 修复 H1：批量回查消息类型（此前 groupBy 内逐附件查 Messages → N+1）
                    val msgIds = allAttachments.mapNotNull { it[EncryptedAttachments.messageId] }.distinct()
                    val msgTypeById = if (msgIds.isEmpty()) emptyMap() else
                        Messages.select(Messages.id, Messages.type)
                            .where { Messages.id inList msgIds }
                            .associate { it[Messages.id] to it[Messages.type] }
                    val byMime = allAttachments.groupBy { att ->
                        val msgId = att[EncryptedAttachments.messageId]
                        if (msgId == null) "orphan"
                        else when (msgTypeById[msgId]) {
                            "IMAGE" -> "image"
                            "VIDEO" -> "video"
                            "FILE" -> "file"
                            "VOICE" -> "voice"
                            else -> "other"
                        }
                    }.map { (category, list) ->
                        StorageBreakdownEntry(
                            category = category,
                            fileCount = list.size.toLong(),
                            totalBytes = list.sumOf { it[EncryptedAttachments.cipherSize] }
                        )
                    }.sortedByDescending { it.totalBytes }
                    val quotaPerUser = 1024L * 1024L * 1024L // 1 GB
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val now = System.currentTimeMillis()
                val dayMs = 86_400_000L
                val points = 7
                val trends = transaction {
                    // 8.48 修复 M2：按天 GROUP BY 聚合（此前 7 天 × 7 表 = 49 次 count）
                    val startMs = now - (points - 1) * dayMs
                    val dayStartMs = startMs - (startMs % dayMs)
                    fun dayCounts(col: Column<Long>): Map<Long, Long> {
                        val bucket = dayBucketExpression(col)
                        return col.table.slice(bucket, col.count()).selectAll()
                            .where { col greaterEq dayStartMs }
                            .groupBy(bucket)
                            .toList()
                            .associate { it[bucket] to it[col.count()].toLong() }
                    }
                    val userCounts = dayCounts(Users.lastSeen)
                    val messageCounts = dayCounts(Messages.timestamp)
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
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
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
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
                recordAdminAudit(actorId, "ADMIN_AUDIT_EXPORTED", "count=${logs.size}")
                val csv = buildString {
                    append('\uFEFF').append("id,actorId,targetUserId,action,detail,createdAt\r\n")
                    logs.forEach { log ->
                        append(csvCell(log.id)).append(',')
                        append(csvCell(log.actorId)).append(',')
                        append(csvCell(log.targetUserId)).append(',')
                        append(csvCell(log.action)).append(',')
                        append(csvCell(log.detail)).append(',')
                        append(log.createdAt).append("\r\n")
                    }
                }
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-admin-audit-${System.currentTimeMillis()}.csv\""
                )
                call.respondText(csv, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
            }

            // ─── 用户管理 ─────────────────────
            get("/users") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val status = call.request.queryParameters["status"]?.trim()?.takeIf { it.isNotBlank() }
                val users = transaction {
                    val escapedSearch = search?.let { escapeLikePattern(it) }
                    val base = if (escapedSearch != null) {
                        Users.selectAll().where { Users.name like "%$escapedSearch%" or (Users.email like "%$escapedSearch%") }
                    } else Users.selectAll()
                    val now = System.currentTimeMillis()
                    val filtered = when (status) {
                        "active" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil lessEq now) }
                        "banned" -> base.andWhere { Users.suspendedUntil greater now }
                        "deleted" -> base.andWhere { Users.deletedAt.isNotNull() }
                        "online" -> base.andWhere { Users.isOnline eq true }
                        else -> base
                    }
                    filtered.orderBy(Users.lastSeen to SortOrder.DESC, Users.id to SortOrder.DESC).limit(limit, offset)
                        .map { it.toUserAdminResponse() }
                }
                call.respond(users)
            }

            get("/users/{id}") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                val user = transaction { Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUserAdminResponse() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                call.respond(user)
            }

            get("/users/{id}/detail") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                val detail = transaction {
                    val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                        ?: return@transaction null
                    UserDetailAdminResponse(
                        id = row[Users.id],
                        name = row[Users.name],
                        email = row[Users.email],
                        isModerator = row[Users.isModerator],
                        lastActiveAt = row[Users.lastSeen],
                        suspendedUntil = row[Users.suspendedUntil],
                        postRestrictedUntil = row[Users.postRestrictedUntil],
                        messageRestrictedUntil = row[Users.messageRestrictedUntil],
                        deletedAt = row[Users.deletedAt],
                        messageCount = Messages.selectAll().where { Messages.senderId eq id }.count(),
                        postCount = Posts.selectAll().where { Posts.authorId eq id }.count(),
                        commentCount = PostComments.selectAll().where { PostComments.authorId eq id }.count(),
                        chatCount = ChatParticipants.selectAll().where { ChatParticipants.userId eq id }.count(),
                        pushTokenCount = PushTokens.selectAll().where { PushTokens.userId eq id }.count(),
                        reportCount = Reports.selectAll().where {
                            (Reports.reporterId eq id) or (Reports.targetId eq id)
                        }.count(),
                        avatar = row[Users.avatar]
                    )
                } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                call.respond(detail)
            }

            get("/disposition-templates") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                call.respond(
                    DispositionTemplatesResponse(
                        banReasons = AdminDispositionPolicy.banReasonTemplates.map {
                            DispositionReasonDto(
                                code = it.code,
                                labelZh = it.labelZh,
                                defaultDays = it.defaultDays,
                                requiresCustomNote = it.requiresCustomNote
                            )
                        },
                        muteReasons = AdminDispositionPolicy.muteReasonTemplates.map {
                            MuteReasonDto(
                                code = it.code,
                                labelZh = it.labelZh,
                                durationHours = it.durationHours,
                                requiresCustomNote = it.requiresCustomNote
                            )
                        },
                        postRestrictReasons = AdminDispositionPolicy.postRestrictReasonTemplates.map {
                            DispositionReasonDto(
                                code = it.code,
                                labelZh = it.labelZh,
                                defaultDays = it.defaultDays,
                                requiresCustomNote = it.requiresCustomNote
                            )
                        },
                        messageRestrictReasons = AdminDispositionPolicy.messageRestrictReasonTemplates.map {
                            DispositionReasonDto(
                                code = it.code,
                                labelZh = it.labelZh,
                                defaultDays = it.defaultDays,
                                requiresCustomNote = it.requiresCustomNote
                            )
                        },
                        unbanReasonCode = AdminDispositionPolicy.unbanReasonCode,
                        unmuteReasonCode = AdminDispositionPolicy.unmuteReasonCode,
                        unrestrictPostsReasonCode = AdminDispositionPolicy.unrestrictPostsReasonCode,
                        unrestrictMessagesReasonCode = AdminDispositionPolicy.unrestrictMessagesReasonCode,
                        appealNoticeZh = AdminDispositionPolicy.APPEAL_NOTICE_ZH,
                        maxBanDays = AdminDispositionPolicy.MAX_BAN_DAYS,
                        maxPostRestrictDays = AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS,
                        maxMessageRestrictDays = AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS
                    )
                )
            }

            put("/users/{id}/status") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能修改自己的管理状态"))
                if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能修改其他超级管理员"))
                val req = call.receiveAdminJson<UpdateUserStatusRequest>()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                val now = System.currentTimeMillis()
                val bannedUntil = req.bannedUntil ?: 0L
                if (bannedUntil < 0 || bannedUntil > now + MAX_ADMIN_SUSPEND_MS || (bannedUntil in 1..now)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("封禁截止时间无效"))
                }
                val banDays = if (bannedUntil <= 0L) {
                    0
                } else {
                    // ceil days for template validation; exact until still stored from client/server clock
                    (((bannedUntil - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, AdminDispositionPolicy.MAX_BAN_DAYS)
                }
                val disposition = AdminDispositionPolicy.validateDisposition(
                    banDays = banDays,
                    reasonCode = req.reasonCode,
                    note = req.note
                )
                val okDisposition = when (disposition) {
                    is AdminDispositionPolicy.DispositionValidation.Invalid ->
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(disposition.message))
                    is AdminDispositionPolicy.DispositionValidation.Ok -> disposition
                }
                val updated = transaction {
                    // 9.154：以 update 影响行数为准——firstOrNull 与 update 之间并发删除会
                    // 让封禁落空却仍写审计行、回 200 并轮换会话
                    val changed = Users.update({ Users.id eq id }) { it[suspendedUntil] = bannedUntil }
                    if (changed == 0) return@transaction false
                    ModerationAuditLog.insert {
                        it[userId] = id
                        it[action] = "ADMIN_STATUS_UPDATE"
                        it[detail] = AdminDispositionPolicy.auditDetail(
                            bannedUntil = bannedUntil,
                            reasonCode = okDisposition.reasonCode,
                            note = okDisposition.note
                        ).take(com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS)
                        it[ModerationAuditLog.actorId] = actorId
                        it[createdAt] = now
                    }
                    true
                }
                if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                // 生效中的封禁需立刻废掉已签发会话，避免仅靠写路径的 suspended 检查被绕过
                if (bannedUntil > now) {
                    authTokenRepo.rotateAccessTokenVersion(id)
                    // 封禁后旧设备不得再收推送
                    PushTokenRepository().removeAllForUser(id)
                    disconnectUserSessions(id, "账号已被临时封禁")
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("reasonCode", okDisposition.reasonCode)
put("appealNoticeZh", AdminDispositionPolicy.APPEAL_NOTICE_ZH)
                }
            )
            }

            put("/users/{id}/post-restriction") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发帖权限"))
                if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制其他超级管理员"))
                val req = call.receiveAdminJson<UpdatePostRestrictionRequest>()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                val now = System.currentTimeMillis()
                val postRestrictedUntil = req.postRestrictedUntil ?: 0L
                if (postRestrictedUntil < 0 ||
                    postRestrictedUntil > now + AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS * 86_400_000L ||
                    (postRestrictedUntil in 1..now)
                ) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁动态截止时间无效"))
                }
                val durationDays = if (postRestrictedUntil <= 0L) {
                    0
                } else {
                    (((postRestrictedUntil - now) + 86_399_999L) / 86_400_000L)
                        .toInt()
                        .coerceIn(1, AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS)
                }
                val disposition = AdminDispositionPolicy.validatePostRestrict(
                    durationDays = durationDays,
                    reasonCode = req.reasonCode,
                    note = req.note
                )
                val okDisposition = when (disposition) {
                    is AdminDispositionPolicy.DispositionValidation.Invalid ->
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(disposition.message))
                    is AdminDispositionPolicy.DispositionValidation.Ok -> disposition
                }
                val updated = transaction {
                    // 9.154：同封禁端点——以 update 影响行数为准
                    val changed = Users.update({ Users.id eq id }) { it[Users.postRestrictedUntil] = postRestrictedUntil }
                    if (changed == 0) return@transaction false
                    ModerationAuditLog.insert {
                        it[userId] = id
                        it[action] = "ADMIN_POST_RESTRICT"
                        it[detail] = AdminDispositionPolicy.auditPostRestrictDetail(
                            postRestrictedUntil = postRestrictedUntil,
                            reasonCode = okDisposition.reasonCode,
                            note = okDisposition.note
                        ).take(com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS)
                        it[ModerationAuditLog.actorId] = actorId
                        it[createdAt] = now
                    }
                    true
                }
                if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                call.respond(
                    buildJsonObject {
                        put("status", "ok")
                        put("postRestrictedUntil", postRestrictedUntil)
                        put("reasonCode", okDisposition.reasonCode)
                        put("appealNoticeZh", AdminDispositionPolicy.APPEAL_NOTICE_ZH)
                    }
                )
            }

            put("/users/{id}/message-restriction") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发消息权限"))
                if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制系统主管理员"))
                val req = call.receiveAdminJson<UpdateMessageRestrictionRequest>()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                val now = System.currentTimeMillis()
                val messageRestrictedUntil = req.messageRestrictedUntil ?: 0L
                if (messageRestrictedUntil < 0 ||
                    messageRestrictedUntil > now + AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L ||
                    (messageRestrictedUntil in 1..now)
                ) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁消息截止时间无效"))
                }
                val durationDays = if (messageRestrictedUntil <= 0L) {
                    0
                } else {
                    (((messageRestrictedUntil - now) + 86_399_999L) / 86_400_000L)
                        .toInt()
                        .coerceIn(1, AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS)
                }
                val disposition = AdminDispositionPolicy.validateMessageRestrict(
                    durationDays = durationDays,
                    reasonCode = req.reasonCode,
                    note = req.note
                )
                val okDisposition = when (disposition) {
                    is AdminDispositionPolicy.DispositionValidation.Invalid ->
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(disposition.message))
                    is AdminDispositionPolicy.DispositionValidation.Ok -> disposition
                }
                val updated = transaction {
                    // 9.154：同封禁端点——以 update 影响行数为准
                    val changed = Users.update({ Users.id eq id }) { it[Users.messageRestrictedUntil] = messageRestrictedUntil }
                    if (changed == 0) return@transaction false
                    ModerationAuditLog.insert {
                        it[userId] = id
                        it[action] = "ADMIN_MESSAGE_RESTRICT"
                        it[detail] = AdminDispositionPolicy.auditMessageRestrictDetail(
                            messageRestrictedUntil = messageRestrictedUntil,
                            reasonCode = okDisposition.reasonCode,
                            note = okDisposition.note
                        ).take(com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS)
                        it[ModerationAuditLog.actorId] = actorId
                        it[createdAt] = now
                    }
                    true
                }
                if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                call.respond(
                    buildJsonObject {
                        put("status", "ok")
                        put("reasonCode", okDisposition.reasonCode)
                        put("appealNoticeZh", AdminDispositionPolicy.APPEAL_NOTICE_ZH)
                        put("messageRestrictedUntil", messageRestrictedUntil)
                    }
                )
            }


            delete("/users/{id}") {
                if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                if (id == actorId) return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能删除自己的管理员账号"))
                if (AdminAccess.isAdmin(id)) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能删除其他超级管理员"))
                val groupAvatarCandidates = chatRepo.groupAvatarUrlsForParticipant(id)
                val deactivation = userRepo.adminDeactivateAccount(id, actorId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在或已注销"))
                // 8.37：DB 注销已在事务内提交，磁盘清理必须逐项容错——任一失败若抛 500，
                // 重试会命中 404（用户已注销）导致清理无法重做，产生孤儿密文/图片。
                // 失败仅记日志，由存储层 TTL/孤儿清理兜底。
                fun bestEffort(step: String, block: () -> Unit) {
                    runCatching(block).onFailure { e ->
                        org.slf4j.LoggerFactory.getLogger("AdminRouting").warn("admin delete user $id: cleanup $step failed", e)
                    }
                }
                // 空会话级联删除的附件行已不在 DB；先清磁盘，再清仍挂在其他会话上的本人上传
                bestEffort("orphanedAttachments") {
                    deactivation.orphanedAttachmentIds
                        .forEach(com.maodouchat.server.service.EncryptedAttachmentStorage::delete)
                }
                bestEffort("uploaderAttachments") {
                    EncryptedAttachmentRepository().deleteForUploader(id)
                        .forEach(com.maodouchat.server.service.EncryptedAttachmentStorage::delete)
                }
                bestEffort("posts") { postRepo.deleteAllPostsForAuthor(id) }
                bestEffort("postImages") { com.maodouchat.server.service.FileStorageService.deletePostImagesForUser(id) }
                bestEffort("groupAvatars") {
                    groupAvatarCandidates
                        .filterNot(chatRepo::isGroupAvatarUrlReferenced)
                        .forEach { url -> com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(url) }
                }
                bestEffort("avatar") { com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(deactivation.avatarUrl, id) }
                // disconnectUserSessions 是挂起函数：单独 runCatching（inline 内允许挂起调用）
                // 取消必须重抛，避免吞掉协程取消
                runCatching { disconnectUserSessions(id, "账号已被管理员停用") }
                    .onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        org.slf4j.LoggerFactory.getLogger("AdminRouting").warn("admin delete user $id: cleanup disconnect failed", e)
                    }
                call.respond(
                    AdminAccountDeactivatedResponse(
                        status = "deactivated",
                        deletedAt = deactivation.deletedAt
                    )
                )
            }

            // ─── 内容管理（动态 / 评论）─────────
            get("/posts") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
                val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val posts = transaction {
                    val query = Posts.selectAll()
                    if (status != null) query.andWhere { Posts.status eq status }
                    val escapedSearch = search?.let { escapeLikePattern(it) }
                    if (escapedSearch != null) query.andWhere { Posts.content like "%$escapedSearch%" }
                    val rows = query
                        .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                        .limit(limit, offset)
                        .toList()
                    val authorIds = rows.map { it[Posts.authorId] }.distinct()
                    val authorNames = if (authorIds.isEmpty()) emptyMap() else Users.selectAll()
                        .where { Users.id inList authorIds }
                        .associate { it[Users.id] to it[Users.name] }
                    rows.map { it.toPostAdminResponse(authorNames[it[Posts.authorId]].orEmpty()) }
                }
                call.respond(posts)
            }

            delete("/posts/{id}") {
                if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少动态 ID"))
                val ok = postRepo.deletePostForModeration(id)
                if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                recordAdminAudit(actorId, "ADMIN_POST_DELETED", "postId=$id")
                broadcastPostDeleted(id)
                call.respond(
                buildJsonObject {
put("status", "deleted")
                }
            )
            }

            delete("/comments/{id}") {
                if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少评论 ID"))
                val ok = postRepo.deleteCommentForModeration(id)
                if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在"))
                recordAdminAudit(actorId, "ADMIN_COMMENT_DELETED", "commentId=$id")
                call.respond(
                buildJsonObject {
put("status", "deleted")
                }
            )
            }

            get("/comments") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val comments = transaction {
                    val query = (PostComments innerJoin Users).selectAll()
                    val escapedSearch = search?.let { escapeLikePattern(it) }
                    if (escapedSearch != null) {
                        query.andWhere {
                            (PostComments.content like "%$escapedSearch%") or (Users.name like "%$escapedSearch%")
                        }
                    }
                    query.orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
                        .limit(limit, offset)
                        .map {
                            CommentAdminResponse(
                                id = it[PostComments.id],
                                postId = it[PostComments.postId],
                                authorId = it[PostComments.authorId],
                                authorName = it[Users.name],
                                content = it[PostComments.content],
                                createdAt = it[PostComments.createdAt]
                            )
                        }
                }
                call.respond(comments)
            }

            // ─── 群聊管理 ─────────────────────
            get("/chats") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val groupOnly = call.request.queryParameters["groupOnly"] == "true"
                val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val chats = transaction {
                    val query = Chats.selectAll()
                    if (groupOnly) query.andWhere { Chats.isGroup eq true }
                    val escapedSearch = search?.let { escapeLikePattern(it) }
                    if (escapedSearch != null) query.andWhere { Chats.groupName like "%$escapedSearch%" }
                    val rows = query
                        .orderBy(Chats.memberRevision to SortOrder.DESC, Chats.id to SortOrder.DESC)
                        .limit(limit, offset)
                        .toList()
                    val chatIds = rows.map { it[Chats.id] }
                    val countExpr = ChatParticipants.userId.count()
                    val memberCounts: Map<String, Int> = if (chatIds.isEmpty()) emptyMap() else
                        ChatParticipants.slice(ChatParticipants.chatId, countExpr)
                            .selectAll().where { ChatParticipants.chatId inList chatIds }
                            .groupBy(ChatParticipants.chatId)
                            .associate { it[ChatParticipants.chatId] to it[countExpr].toInt() }
                    val lastMsgMap: Map<String, Long> = if (chatIds.isEmpty()) emptyMap() else {
                        val maxExpr = Messages.timestamp.max()
                        Messages.slice(Messages.chatId, maxExpr)
                            .selectAll().where { Messages.chatId inList chatIds }
                            .groupBy(Messages.chatId)
                            .associate { it[Messages.chatId] to (it[maxExpr] ?: 0L) }
                    }
                    rows.map { row ->
                        val chatId = row[Chats.id]
                        ChatAdminResponse(
                            id = chatId,
                            isGroup = row[Chats.isGroup],
                            groupName = row[Chats.groupName],
                            groupAnnouncement = row[Chats.groupAnnouncement],
                            memberCount = memberCounts[chatId] ?: 0,
                            createdAt = 0L,
                            lastActivity = lastMsgMap[chatId] ?: 0L
                        )
                    }
                }
                call.respond(chats)
            }

            delete("/chats/{id}") {
                if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少聊天 ID"))
                val (dissolved, attachmentIds, groupAvatarUrl) = transaction {
                    val chat = Chats.selectAll().where { Chats.id eq id }.forUpdate().firstOrNull()
                        ?: return@transaction Triple(false, emptyList<String>(), null)
                    // 清理关联表（外键约束要求先删除引用表）
                    val messageIds = Messages.select(Messages.id).where { Messages.chatId eq id }.map { it[Messages.id] }
                    if (messageIds.isNotEmpty()) {
                        MessageReactions.deleteWhere { MessageReactions.messageId inList messageIds }
                        ReadReceipts.deleteWhere { ReadReceipts.messageId inList messageIds }
                        StarMessages.deleteWhere { StarMessages.messageId inList messageIds }
                        PinnedMessages.deleteWhere { PinnedMessages.messageId inList messageIds }
                    }
                    val attachmentIds = EncryptedAttachments
                        .select(EncryptedAttachments.id)
                        .where { EncryptedAttachments.chatId eq id }
                        .map { it[EncryptedAttachments.id] }
                    if (attachmentIds.isNotEmpty()) {
                        EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
                    }
                    // FK: direct_chat_pairs / message_mutations → chats
                    DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq id }
                    MessageMutations.deleteWhere { MessageMutations.chatId eq id }
                    SenderKeyDistributions.deleteWhere { SenderKeyDistributions.chatId eq id }
                    AiPreferences.deleteWhere { AiPreferences.chatId eq id }
                    ChatUserSettings.deleteWhere { ChatUserSettings.chatId eq id }
                    GroupAuditLogs.deleteWhere { GroupAuditLogs.chatId eq id }
                    // 群投票/投票记录/Bot 命令日志：与 deleteChatRows 保持一致，避免孤儿行引用已删除的聊天
                    val pollIds = GroupPolls.select(GroupPolls.id)
                        .where { GroupPolls.chatId eq id }
                        .orderBy(GroupPolls.id to org.jetbrains.exposed.sql.SortOrder.ASC)
                        .forUpdate()
                        .map { it[GroupPolls.id] }
                    if (pollIds.isNotEmpty()) {
                        GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
                        GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
                    }
                    val chainIds = GroupChains.select(GroupChains.id)
                        .where { GroupChains.chatId eq id }
                        .map { it[GroupChains.id] }
                    if (chainIds.isNotEmpty()) {
                        GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
                        GroupChains.deleteWhere { GroupChains.chatId eq id }
                    }
                    val pkIds = GroupPkRounds.select(GroupPkRounds.id)
                        .where { GroupPkRounds.chatId eq id }
                        .map { it[GroupPkRounds.id] }
                    if (pkIds.isNotEmpty()) {
                        GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
                        GroupPkRounds.deleteWhere { GroupPkRounds.chatId eq id }
                    }
                    GroupCheckins.deleteWhere { GroupCheckins.chatId eq id }
                    BotCommandLogs.deleteWhere { BotCommandLogs.chatId eq id }
                    Messages.deleteWhere { Messages.chatId eq id }
                    ChatParticipants.deleteWhere { ChatParticipants.chatId eq id }
                    Chats.deleteWhere { Chats.id eq id }
                    Triple(true, attachmentIds, chat[Chats.groupAvatar])
                }
                if (!dissolved) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                // 事务外清磁盘，避免 orphan .bin
                attachmentIds.forEach { runCatching { com.maodouchat.server.service.EncryptedAttachmentStorage.delete(it) } }
                com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(groupAvatarUrl, id)
                recordAdminAudit(actorId, "ADMIN_CHAT_DISSOLVED", "chatId=$id")
                call.respond(
                buildJsonObject {
put("status", "dissolved")
                }
            )
            }

            // ─── 举报管理（admin-jwt 代理） ────
            get("/reports") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val status = call.request.queryParameters["status"]?.trim()?.takeIf { it.isNotBlank() && it != "ALL" }
                call.respond(reportRepo.getReports(status, limit, offset))
            }

            put("/reports/{reportId}/status") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val reportId = call.parameters["reportId"].orEmpty()
                val req = call.receiveAdminJson<UpdateReportStatusRequest>()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                when (val result = reportRepo.updateReportStatus(reportId, actorId, req.status, req.resolutionNote)) {
                    is ReportRepository.UpdateResult.Success -> call.respond(result.report)
                    is ReportRepository.UpdateResult.Failure -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                }
            }

            post("/reports/{reportId}/action") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val reportId = call.parameters["reportId"].orEmpty()
                val req = call.receiveAdminJson<ApplyReportActionRequest>()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                val action = req.action.trim().uppercase()
                val existingReport = reportRepo.getReport(reportId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("举报不存在"))
                // 处置对象 userId 在 mark 前冻结，避免 mark 后内容被删导致限制落空
                val frozenRestrictionTargetUserId: String? = when (action) {
                    "NO_ACTION" -> null
                    "DELETE_CONTENT" -> {
                        if (existingReport.targetType !in setOf("POST", "COMMENT")) {
                            return@post call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("该举报类型不支持从管理面板删除内容，请使用审核员面板处理消息举报")
                            )
                        }
                        null
                    }
                    "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                        val targetUserId = when (existingReport.targetType) {
                            "USER" -> existingReport.targetId
                            "POST" -> postRepo.getPostAuthorId(existingReport.targetId)
                            "COMMENT" -> postRepo.getCommentAuthorId(existingReport.targetId)
                            else -> null
                        }
                        if (targetUserId.isNullOrBlank()) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法定位被处置用户"))
                        }
                        if (targetUserId == actorId) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能处置自己"))
                        }
                        if (AdminAccess.isAdmin(targetUserId)) {
                            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能处置超级管理员"))
                        }
                        targetUserId
                    }
                    else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("处置动作无效"))
                }
                // 原子标记：仅 Applied 时执行副作用
                when (val mark = reportRepo.markActionTaken(reportId, actorId, action, req.resolutionNote)) {
                    is ReportRepository.ActionMarkResult.Failure -> {
                        val status = if (mark.message == "举报不存在") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        return@post call.respond(status, ErrorResponse(mark.message))
                    }
                    is ReportRepository.ActionMarkResult.AlreadyDone ->
                        return@post call.respond(
                buildJsonObject {
put("status", "resolved")
put("action", (mark.report.actionTaken ?: "NO_ACTION"))
                }
            )
                    is ReportRepository.ActionMarkResult.Applied -> {
                        val report = mark.report
                        when (action) {
                            "NO_ACTION" -> Unit
                            "DELETE_CONTENT" -> {
                                when (report.targetType) {
                                    "POST" -> postRepo.deletePostForModeration(report.targetId)
                                    "COMMENT" -> postRepo.deleteCommentForModeration(report.targetId)
                                    else -> Unit
                                }
                            }
                            "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                                val targetUserId = frozenRestrictionTargetUserId
                                if (!targetUserId.isNullOrBlank() &&
                                    targetUserId != actorId &&
                                    !AdminAccess.isAdmin(targetUserId)
                                ) {
                                    userRepo.applyModerationRestriction(targetUserId, action)
                                    if (action == "SUSPEND_24H") {
                                        authTokenRepo.rotateAccessTokenVersion(targetUserId)
                                        PushTokenRepository().removeAllForUser(targetUserId)
                                        disconnectUserSessions(targetUserId, "账号已被临时封禁")
                                    }
                                }
                            }
                        }
                        recordAdminAudit(actorId, "REPORT_ACTION_APPLIED", "reportId=$reportId; action=$action")
                        call.respond(
                buildJsonObject {
put("status", "resolved")
put("action", action)
                }
            )
                    }
                }
            }

            // ─── 风控规则 CRUD ─────────────────
            get("/moderation-rules") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val rules = moderationRuleRepo.getRules()
                call.respond(rules)
            }

            post("/moderation-rules") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val req = call.receiveAdminJson<CreateModerationRuleRequest>()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                val id = runCatching { moderationRuleRepo.createRule(req) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("规则参数无效"))
                recordAdminAudit(call.principal<JWTPrincipal>()!!.payload.subject, "ADMIN_RULE_CREATED", "ruleId=$id")
                call.respond(
                buildJsonObject {
put("id", id)
put("status", "created")
                }
            )
            }

            put("/moderation-rules/{id}") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少规则 ID"))
                val req = call.receiveAdminJson<UpdateModerationRuleRequest>()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                val updated = moderationRuleRepo.updateRule(id, req)
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("规则不存在或参数无效"))
                recordAdminAudit(call.principal<JWTPrincipal>()!!.payload.subject, "ADMIN_RULE_UPDATED", "ruleId=$id")
                call.respond(updated)
            }

            delete("/moderation-rules/{id}") {
                if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少规则 ID"))
                if (!moderationRuleRepo.deleteRule(id)) {
                    return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("规则不存在"))
                }
                recordAdminAudit(call.principal<JWTPrincipal>()!!.payload.subject, "ADMIN_RULE_DELETED", "ruleId=$id")
                call.respond(
                buildJsonObject {
put("status", "deleted")
                }
            )
            }

            // ─── 风控事件监控 ─────────────────
            get("/risk-events") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val needsReviewOnly = call.request.queryParameters["pending"] == "true"
                val events = transaction {
                    val query = RiskEvents.selectAll()
                    if (needsReviewOnly) query.andWhere { RiskEvents.needsReview eq true }
                    query.orderBy(RiskEvents.createdAt to SortOrder.DESC, RiskEvents.id to SortOrder.DESC)
                        .limit(limit, offset)
                        .map {
                            RiskEventAdminResponse(
                                id = it[RiskEvents.id],
                                userId = it[RiskEvents.userId],
                                source = it[RiskEvents.sourceValue],
                                ruleId = it[RiskEvents.ruleId],
                                action = it[RiskEvents.action],
                                matched = it[RiskEvents.matched],
                                referenceId = it[RiskEvents.referenceId],
                                needsReview = it[RiskEvents.needsReview],
                                createdAt = it[RiskEvents.createdAt]
                            )
                        }
                }
                call.respond(events)
            }

            put("/risk-events/{id}/resolve") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少事件 ID"))
                val updated = transaction {
                    val exists = RiskEvents.selectAll().where { RiskEvents.id eq id }.firstOrNull()
                        ?: return@transaction false
                    RiskEvents.update({ RiskEvents.id eq id }) { it[needsReview] = false }
                    true
                }
                if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("事件不存在"))
                recordAdminAudit(actorId, "RISK_EVENT_RESOLVED", "eventId=$id")
                call.respond(
                buildJsonObject {
put("status", "resolved")
                }
            )
            }

            // ─── AI 使用审计（仅元数据，无 prompt/正文；M5-8） ──────────────────
            get("/ai-usage") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeLimit(
                    call.request.queryParameters["limit"]?.toIntOrNull()
                )
                val offset = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeOffset(
                    call.request.queryParameters["offset"]?.toLongOrNull()
                )
                val featureFilter = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeFeatureFilter(
                    call.request.queryParameters["feature"]
                )
                val userFilter = com.maodouchat.server.service.AdminAiAuditPolicy.normalizeUserFilter(
                    call.request.queryParameters["userId"] ?: call.request.queryParameters["q"]
                )
                val logs = transaction {
                    val query = AiAuditLogs.selectAll()
                    if (featureFilter != null) {
                        query.andWhere { AiAuditLogs.feature eq featureFilter }
                    }
                    if (userFilter != null) {
                        query.andWhere { AiAuditLogs.userId eq userFilter }
                    }
                    val rows = query
                        .orderBy(AiAuditLogs.createdAt to SortOrder.DESC, AiAuditLogs.id to SortOrder.DESC)
                        .limit(limit, offset)
                        .toList()
                    // 9.137：input_tokens/output_tokens 已进 Table 单例（启动迁移补列），
                    // 这里仍用参数化 SQL 按 id 批量回填，避免逐行二次查询。
                    val tokenById: Map<String, Pair<Long?, Long?>> = if (rows.isEmpty()) emptyMap() else {
                        val ids = rows.map { it[AiAuditLogs.id] }
                        val placeholders = List(ids.size) { "?" }.joinToString(",")
                        exec(
                            "SELECT id, input_tokens, output_tokens FROM ai_audit_logs WHERE id IN ($placeholders)",
                            ids.map { VarCharColumnType() to it }
                        ) { rs ->
                            val m = mutableMapOf<String, Pair<Long?, Long?>>()
                            while (rs.next()) {
                                val input = rs.getLong(2)
                                val inputTokens: Long? = if (rs.wasNull()) null else input
                                val output = rs.getLong(3)
                                val outputTokens: Long? = if (rs.wasNull()) null else output
                                m[rs.getString(1)] = inputTokens to outputTokens
                            }
                            m
                        } ?: emptyMap()
                    }
                    rows.map {
                        val tokens = tokenById[it[AiAuditLogs.id]]
                        // Never project chatId / prompt / body into admin responses.
                        com.maodouchat.server.service.AdminAiAuditPolicy.toAdminResponse(
                            id = it[AiAuditLogs.id],
                            userId = it[AiAuditLogs.userId],
                            feature = it[AiAuditLogs.feature],
                            model = it[AiAuditLogs.model],
                            status = it[AiAuditLogs.status],
                            inputChars = it[AiAuditLogs.inputChars],
                            contextMessages = it[AiAuditLogs.contextMessages],
                            durationMs = it[AiAuditLogs.durationMs],
                            error = it[AiAuditLogs.error],
                            createdAt = it[AiAuditLogs.createdAt],
                            inputTokens = tokens?.first,
                            outputTokens = tokens?.second
                        )
                    }
                }
                call.respond(logs)
            }

            // ─── 推送令牌管理 ─────────────────
            get("/push-tokens") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val tokens = transaction {
                    val query = PushTokens.selectAll()
                    if (search != null) query.andWhere { PushTokens.userId eq search }
                    query.orderBy(
                        PushTokens.updatedAt to SortOrder.DESC,
                        PushTokens.userId to SortOrder.DESC,
                        PushTokens.deviceId to SortOrder.DESC
                    )
                        .limit(limit, offset)
                        .map {
                            PushTokenAdminResponse(
                                userId = it[PushTokens.userId],
                                deviceId = it[PushTokens.deviceId],
                                platform = it[PushTokens.platform],
                                timezoneOffsetMinutes = it[PushTokens.timezoneOffsetMinutes],
                                updatedAt = it[PushTokens.updatedAt]
                            )
                        }
                }
                call.respond(tokens)
            }

            put("/bots/{botId}/enabled") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val enabled = runCatching {
                    val p = adminJson.parseToJsonElement(bodyText).jsonObject["enabled"]?.jsonPrimitive
                    p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
                }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
                val updated = transaction {
                    val changed = com.maodouchat.server.db.BotApps.update({ com.maodouchat.server.db.BotApps.id eq botId }) {
                        it[com.maodouchat.server.db.BotApps.enabled] = enabled
                        it[com.maodouchat.server.db.BotApps.updatedAt] = System.currentTimeMillis()
                    }
                    if (changed == 1) com.maodouchat.server.repository.BotRepository.get(botId) else null
                } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
                recordAdminAudit(adminId, "bot_enabled", "bot=$botId;enabled=$enabled")
                call.respond(updated)
            }

            get("/bots") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(com.maodouchat.server.repository.BotRepository.adminList(limit, offset))
            }

            
            // ─── Ops snapshot (bots + polls + capture-related volume) ───
            get("/ops-snapshot") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val snap = transaction {
                    val botTotal = com.maodouchat.server.db.BotApps.selectAll().count()
                    val botEnabled = com.maodouchat.server.db.BotApps.selectAll()
                        .where { com.maodouchat.server.db.BotApps.enabled eq true }.count()
                    val botWithWebhook = com.maodouchat.server.db.BotApps.selectAll()
                        .mapNotNull { row ->
                            val url = row[com.maodouchat.server.db.BotApps.webhookUrl]
                            val enabled = row[com.maodouchat.server.db.BotApps.enabled]
                            if (enabled && !url.isNullOrBlank()) 1 else null
                        }.size.toLong()
                    val pollTotal = com.maodouchat.server.db.GroupPolls.selectAll().count()
                    val pollOpen = com.maodouchat.server.db.GroupPolls.selectAll()
                        .where { com.maodouchat.server.db.GroupPolls.closed eq false }.count()
                    val voteTotal = com.maodouchat.server.db.GroupPollVotes.selectAll().count()
                    val msgTotal = com.maodouchat.server.db.Messages.selectAll().count()
                    val userTotal = com.maodouchat.server.db.Users.selectAll().count()
                    OpsSnapshotResponse(
                        users = userTotal,
                        messages = msgTotal,
                        botsTotal = botTotal,
                        botsEnabled = botEnabled,
                        botsWithWebhook = botWithWebhook,
                        pollsTotal = pollTotal,
                        pollsOpen = pollOpen,
                        pollVotes = voteTotal,
                        generatedAt = System.currentTimeMillis()
                    )
                }
                call.respond(snap)
            }

            // ─── Blind watermark forensics ──────────────
            
            
            
            
            get("/security-snapshot") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val now = System.currentTimeMillis()
                val sealedOn = RuntimeConfigService.isSealedSenderEnabled()
                val botsOn = RuntimeConfigService.isBotsAllowed()
                val aiOn = RuntimeConfigService.isAiEnabled()
                val maint = RuntimeConfigService.isMaintenanceMode()
                val regOpen = RuntimeConfigService.isRegistrationAllowed()
                val ipBlocks = RuntimeConfigService.ipBlocklist().size
                val msgRate = RuntimeConfigService.maxMessagePerMinute()
                val online = try {
                    com.maodouchat.server.plugins.onlineUserIds().size
                } catch (_: Exception) {
                    0
                }
                val (users, activeSessions, riskOpen) = transaction {
                    val u = Users.selectAll().count()
                    val s = runCatching {
                        // best-effort; table may vary
                        0L
                    }.getOrDefault(0L)
                    val r = runCatching {
                        RiskEvents.selectAll().where { RiskEvents.needsReview eq true }.count()
                    }.getOrDefault(0L)
                    Triple(u, s, r)
                }
                call.respond(
                buildJsonObject {
put("generatedAt", now)
put("onlineUsers", online)
put("usersTotal", users)
put("openRiskEvents", riskOpen)
put("flags", buildJsonObject {
put("sealedSenderEnabled", sealedOn)
put("botsAllowed", botsOn)
put("aiEnabled", aiOn)
put("maintenanceMode", maint)
put("registrationOpen", regOpen)
put("pqxdhPreview", RuntimeConfigService.isPqxdhPreviewEnabled())
put("secretChatRequired", RuntimeConfigService.isSecretChatRequired())
put("captureAlertEnabled", RuntimeConfigService.isCaptureAlertEnabled())
put("mediaUploadEnabled", RuntimeConfigService.isMediaUploadEnabled())
put("groupPlayEnabled", RuntimeConfigService.isGroupPlayEnabled())
put("linkPreviewEnabled", RuntimeConfigService.isLinkPreviewEnabled())
put("voiceMessagesEnabled", RuntimeConfigService.isVoiceMessagesEnabled())
put("reactionsEnabled", RuntimeConfigService.isReactionsEnabled())
put("stickersEnabled", RuntimeConfigService.isStickersEnabled())
put("silentSendEnabled", RuntimeConfigService.isSilentSendEnabled())
put("callsEnabled", RuntimeConfigService.isCallsEnabled())
put("scheduledMessagesEnabled", RuntimeConfigService.isScheduledMessagesEnabled())
put("viewOnceEnabled", RuntimeConfigService.isViewOnceEnabled())
put("liveLocationEnabled", RuntimeConfigService.isLiveLocationEnabled())
put("markdownEnabled", RuntimeConfigService.isMarkdownEnabled())
put("typingIndicatorsEnabled", RuntimeConfigService.isTypingIndicatorsEnabled())
put("readReceiptsEnabled", RuntimeConfigService.isReadReceiptsEnabled())
put("presenceEnabled", RuntimeConfigService.isPresenceEnabled())
put("messageStarringEnabled", RuntimeConfigService.isMessageStarringEnabled())
put("chatExportEnabled", RuntimeConfigService.isChatExportEnabled())
put("messageForwardingEnabled", RuntimeConfigService.isMessageForwardingEnabled())
put("globalSearchEnabled", RuntimeConfigService.isGlobalSearchEnabled())
put("friendRequestsEnabled", RuntimeConfigService.isFriendRequestsEnabled())
put("chatFoldersEnabled", RuntimeConfigService.isChatFoldersEnabled())
put("postsEnabled", RuntimeConfigService.isPostsEnabled())
put("blockReportEnabled", RuntimeConfigService.isBlockReportEnabled())
put("chatArchiveEnabled", RuntimeConfigService.isChatArchiveEnabled())
put("nearbyEnabled", RuntimeConfigService.isNearbyEnabled())
put("chatPinEnabled", RuntimeConfigService.isChatPinEnabled())
put("markedUnreadEnabled", RuntimeConfigService.isMarkedUnreadEnabled())
put("chatMuteEnabled", RuntimeConfigService.isChatMuteEnabled())
put("disappearingMessagesEnabled", RuntimeConfigService.isDisappearingMessagesEnabled())
put("chatLockEnabled", RuntimeConfigService.isChatLockEnabled())
put("messageEditEnabled", RuntimeConfigService.isMessageEditEnabled())
put("messagePinEnabled", RuntimeConfigService.isMessagePinEnabled())
put("messageRevokeEnabled", RuntimeConfigService.isMessageRevokeEnabled())
put("pollsEnabled", RuntimeConfigService.isPollsEnabled())
put("appLockEnabled", RuntimeConfigService.isAppLockEnabled())
put("chatDraftsEnabled", RuntimeConfigService.isChatDraftsEnabled())
put("aiTranslateEnabled", RuntimeConfigService.isAiTranslateEnabled())
put("groupInvitesEnabled", RuntimeConfigService.isGroupInvitesEnabled())
put("mentionsEnabled", RuntimeConfigService.isMentionsEnabled())
put("nudgeEnabled", RuntimeConfigService.isNudgeEnabled())
put("safetyCodeEnabled", RuntimeConfigService.isSafetyCodeEnabled())
put("qrCodeEnabled", RuntimeConfigService.isQrCodeEnabled())
put("contactCardEnabled", RuntimeConfigService.isContactCardEnabled())
put("spoilerMediaEnabled", RuntimeConfigService.isSpoilerMediaEnabled())
put("autoDownloadEnabled", RuntimeConfigService.isAutoDownloadEnabled())
put("staticLocationEnabled", RuntimeConfigService.isStaticLocationEnabled())
put("fileShareEnabled", RuntimeConfigService.isFileShareEnabled())
put("secretChatEnabled", RuntimeConfigService.isSecretChatEnabled())
put("screenSecureRuntimeEnabled", RuntimeConfigService.isScreenSecureRuntimeEnabled())
put("imageSendEnabled", RuntimeConfigService.isImageSendEnabled())
put("videoSendEnabled", RuntimeConfigService.isVideoSendEnabled())
put("aiSummaryEnabled", RuntimeConfigService.isAiSummaryEnabled())
put("aiRewriteEnabled", RuntimeConfigService.isAiRewriteEnabled())
put("aiSuggestRepliesEnabled", RuntimeConfigService.isAiSuggestRepliesEnabled())
put("aiTranscribeEnabled", RuntimeConfigService.isAiTranscribeEnabled())
put("aiAnalyzeImageEnabled", RuntimeConfigService.isAiAnalyzeImageEnabled())
put("aiGroupAssistantEnabled", RuntimeConfigService.isAiGroupAssistantEnabled())
put("aiAnalyzeFileEnabled", RuntimeConfigService.isAiAnalyzeFileEnabled())
put("aiSemanticSearchEnabled", RuntimeConfigService.isAiSemanticSearchEnabled())
put("gifSendEnabled", RuntimeConfigService.isGifSendEnabled())
put("blindWatermarkEnabled", RuntimeConfigService.isBlindWatermarkEnabled())
})
put("limits", buildJsonObject {
put("maxMessagePerMin", msgRate)
put("ipBlocklistCount", ipBlocks)
put("maxGroupSize", RuntimeConfigService.getInt(RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200))
put("minAppVersion", RuntimeConfigService.minAppVersion())
put("maxBotsPerUser", RuntimeConfigService.maxBotsPerUser())
})
put("banner", RuntimeConfigService.get(RuntimeConfigService.KEY_GLOBAL_BANNER))
put("forceE2eeBanner", RuntimeConfigService.get(RuntimeConfigService.KEY_FORCE_E2EE_BANNER))
put("publicAnnouncement", RuntimeConfigService.get(RuntimeConfigService.KEY_PUBLIC_ANNOUNCEMENT))
                }
            )
            }

get("/settings") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                call.respond(
                buildJsonObject {
put("settings", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.all())))
put("defaults", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.defaults())))
put("envAllowRegistration", ServerConfig.allowRegistration)
                }
            )
            }

            put("/settings") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val settingsObj = obj["settings"]?.jsonObject ?: obj
                val updates = settingsObj.mapNotNull { (k, v) ->
                    val s = runCatching { v.jsonPrimitive.content }.getOrNull() ?: return@mapNotNull null
                    k to s
                }.toMap()
                if (updates.isEmpty()) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("no settings"))
                }
                val applied = RuntimeConfigService.setMany(updates, actorId)
                recordAdminAudit(actorId, "ADMIN_SETTINGS_UPDATE", "keys=${updates.keys.joinToString(",")}")
                call.respond(
                buildJsonObject {
put("status", "ok")
put("settings", Json.parseToJsonElement(Json.encodeToString(applied)))
                }
            )
            }

            get("/users/{id}/sessions") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing user id"))
                val includeRevoked = call.request.queryParameters["includeRevoked"] == "1"
                val sessions = authTokenRepo.listActiveRefreshSessions(id, includeRevoked = includeRevoked).map { s ->
                    buildJsonObject {
                        put("tokenHashPrefix", s.tokenHashPrefix)
                        put("createdAt", s.createdAt)
                        put("expiresAt", s.expiresAt)
                        s.revokedAt?.let { put("revokedAt", it) }
                        put("active", s.revokedAt == null && s.expiresAt > System.currentTimeMillis())
                    }
                }
                val devices = transaction {
                    SignalDevices.selectAll()
                        .where { SignalDevices.userId eq id }
                        .orderBy(SignalDevices.lastSeenAt to SortOrder.DESC)
                        .map {
                            buildJsonObject {
                                put("deviceId", it[SignalDevices.deviceId])
                                put("deviceName", it[SignalDevices.deviceName])
                                put("status", it[SignalDevices.status])
                                put("lastSeenAt", it[SignalDevices.lastSeenAt])
                                put("createdAt", it[SignalDevices.createdAt])
                            }
                        }
                }
                val push = transaction {
                    PushTokens.selectAll()
                        .where { PushTokens.userId eq id }
                        .orderBy(PushTokens.updatedAt to SortOrder.DESC)
                        .map {
                            buildJsonObject {
                                put("deviceId", it[PushTokens.deviceId])
                                put("platform", it[PushTokens.platform])
                                put("updatedAt", it[PushTokens.updatedAt])
                            }
                        }
                }
                call.respond(
                buildJsonObject {
put("userId", id)
put("refreshSessions", JsonArray(sessions))
put("activeRefreshCount", authTokenRepo.countActiveRefreshSessions(id))
put("signalDevices", JsonArray(devices))
put("pushTokens", JsonArray(push))
                }
            )
            }


            post("/users/{id}/sessions/revoke") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing user id"))
                if (AdminAccess.isAdmin(id) && id != actorId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot revoke master admin sessions"))
                }
                if (userRepo.getById(id) == null) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                }
                val body = call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("request body is too large or unreadable"))
                val obj = if (body.isBlank()) null else {
                    runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                }
                val prefixElement = obj?.get("tokenHashPrefix")
                val prefix = if (prefixElement == null) "" else {
                    (prefixElement as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.trim()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("tokenHashPrefix must be a string"))
                }
                val allElement = obj?.get("all")
                val revokeAll = if (allElement == null) false else {
                    // 严格 JSON boolean：字符串 "true"/"false" 一律拒绝（booleanOrNull 会宽松解析字符串）
                    (allElement as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.booleanOrNull
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("all must be a boolean"))
                }
                if (!revokeAll && prefix.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("tokenHashPrefix or all=true is required")
                    )
                }
                if (revokeAll && prefix.isNotBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("tokenHashPrefix and all=true are mutually exclusive")
                    )
                }
                if (!revokeAll && !prefix.matches(Regex("^[0-9a-fA-F]{12,64}$"))) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("tokenHashPrefix must be 12-64 hexadecimal characters")
                    )
                }
                val (revoked, revokedSessionIds) = if (revokeAll) {
                    val activeCount = authTokenRepo.countActiveRefreshSessions(id)
                    authTokenRepo.rotateAccessTokenVersion(id)
                    activeCount to emptySet<String>()
                } else {
                    val result = authTokenRepo.revokeByHashPrefixWithSessions(id, prefix)
                    result.count to result.sessionIds
                }
                if (revokeAll) {
                    bestEffortAdminDisconnect { disconnectUserSessions(id, "admin session revoke") }
                } else if (revokedSessionIds.isNotEmpty()) {
                    bestEffortAdminDisconnect {
                        disconnectUserSessionsByAuthSessionIds(id, revokedSessionIds, "admin session revoke")
                    }
                }
                transaction {
                    ModerationAuditLog.insert {
                        it[ModerationAuditLog.actorId] = actorId
                        it[ModerationAuditLog.userId] = id
                        it[ModerationAuditLog.action] = "ADMIN_SESSION_REVOKE"
                        it[ModerationAuditLog.detail] = if (revokeAll) "all=$revoked" else "prefix=$prefix count=$revoked"
                        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                    }
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("revoked", revoked)
put("userId", id)
                }
            )
            }

            get("/messages/search") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val q = call.request.queryParameters["q"]?.trim().orEmpty()
                val chatId = call.request.queryParameters["chatId"]?.trim().orEmpty()
                val userId = call.request.queryParameters["userId"]?.trim().orEmpty()
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                if (q.isBlank() && chatId.isBlank() && userId.isBlank()) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("q or chatId or userId required"))
                }
                // Metadata search only: E2EE payloads are opaque; match non-encrypted types / prefixes.
                val rows = transaction {
                    var query = Messages.selectAll()
                    if (chatId.isNotBlank()) query = query.andWhere { Messages.chatId eq chatId }
                    if (userId.isNotBlank()) query = query.andWhere { Messages.senderId eq userId }
                    if (q.isNotBlank()) {
                        val like = "%" + escapeLikePattern(q.take(80)) + "%"
                        query = query.andWhere {
                            (Messages.id like like) or
                                (Messages.type like like) or
                                (
                                    (Messages.type inList listOf("SYSTEM", "NUDGE", "TEXT", "MARKDOWN")) and
                                        (Messages.content like like)
                                )
                        }
                    }
                    query.orderBy(Messages.timestamp to SortOrder.DESC, Messages.id to SortOrder.DESC)
                        .limit(limit, offset.toLong())
                        .map {
                            // 9.131：contentPreview 仅投影平台明文类型（SYSTEM/NUDGE 等 bot/系统文案）。
                            // 用户消息的 content 是 E2EE 密文载荷——「Metadata search only」原则下
                            // 不得把密文字节投进管理端响应（此前对所有类型 take(120) 原样输出）
                            val platformPlaintext = it[Messages.type] in setOf("SYSTEM", "NUDGE")
                            buildJsonObject {
                                put("id", it[Messages.id])
                                put("chatId", it[Messages.chatId])
                                put("senderId", it[Messages.senderId])
                                put("type", it[Messages.type])
                                put("timestamp", it[Messages.timestamp])
                                put("status", it[Messages.status])
                                put("sealedSender", runCatching { it[Messages.sealedSender] }.getOrDefault(false))
                                put("contentPreview", if (platformPlaintext) it[Messages.content].take(120) else "")
                                put("e2eeLikely", it[Messages.content].length > 40 && !it[Messages.content].startsWith("{") && it[Messages.type] !in setOf("SYSTEM", "NUDGE"))
                            }
                        }
                }
                call.respond(
                buildJsonObject {
put("items", JsonArray(rows))
put("count", rows.size)
put("limit", limit)
put("offset", offset)
                }
            )
            }

post("/broadcast") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty().take(2000)
                if (text.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("text required"))
                val title = obj["title"]?.jsonPrimitive?.content?.trim()?.take(120).orEmpty().ifBlank { "System" }
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("title", title)
                    put("text", text)
                    put("actorId", actorId)
                    put("ts", System.currentTimeMillis())
                }.toString()
                // Match client WsMessage(type, payload:String) encoding used by sockets.
                val envelope = kotlinx.serialization.json.Json.encodeToString(
                    WsMessage.serializer(),
                    WsMessage(type = "ADMIN_BROADCAST", payload = payload)
                )
                // Fanout to all currently online sessions (best-effort live notice).
                val onlineIds = try {
                    com.maodouchat.server.plugins.onlineUserIds()
                } catch (_: Exception) {
                    emptyList()
                }
                var delivered = 0
                for (uid in onlineIds) {
                    try {
                        com.maodouchat.server.plugins.sendToUser(uid, envelope)
                        delivered++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                    }
                }
                transaction {
                    ModerationAuditLog.insert {
                        it[ModerationAuditLog.actorId] = actorId
                        it[ModerationAuditLog.userId] = null
                        it[ModerationAuditLog.action] = "ADMIN_BROADCAST"
                        it[ModerationAuditLog.detail] = text.take(400)
                        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                    }
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("onlineTargets", onlineIds.size)
put("delivered", delivered)
                }
            )
            }

            put("/users/{id}/moderator") {
                if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing user id"))
                if (AdminAccess.isAdmin(id)) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot change master admin"))
                }
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val enabled = runCatching {
                    val el = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
                    el?.booleanOrNull ?: el?.content?.toBooleanStrictOrNull()
                }.getOrNull()
                if (enabled == null) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled bool required"))
                val ok = transaction {
                    if (Users.selectAll().where { Users.id eq id }.firstOrNull() == null) return@transaction false
                    Users.update({ Users.id eq id }) {
                        it[Users.isModerator] = enabled
                    }
                    ModerationAuditLog.insert {
                        it[ModerationAuditLog.actorId] = actorId
                        it[ModerationAuditLog.userId] = id
                        it[ModerationAuditLog.action] = if (enabled) "ADMIN_GRANT_MODERATOR" else "ADMIN_REVOKE_MODERATOR"
                        it[ModerationAuditLog.detail] = "enabled=$enabled"
                        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                    }
                    true
                }
                if (!ok) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                call.respond(
                buildJsonObject {
put("status", "ok")
put("userId", id)
put("isModerator", enabled)
                }
            )
            }

post("/users/{id}/force-logout") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing user id"))
                if (AdminAccess.isAdmin(id) && id != actorId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot force-logout master admin"))
                }
                if (authTokenRepo.rotateAccessTokenVersion(id) == 0L) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                }
                bestEffortAdminDisconnect { disconnectUserSessions(id, "admin force logout") }
                transaction {
                    ModerationAuditLog.insert {
                        it[ModerationAuditLog.actorId] = actorId
                        it[ModerationAuditLog.userId] = id
                        it[ModerationAuditLog.action] = "ADMIN_FORCE_LOGOUT"
                        it[ModerationAuditLog.detail] = "force logout"
                        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                    }
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("userId", id)
                }
            )
            }

            post("/users/{id}/disable-totp") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing user id"))
                if (AdminAccess.isAdmin(id) && id != actorId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot disable TOTP for another master admin"))
                }
                val updated = transaction {
                    val changed = Users.update({ Users.id eq id }) {
                        it[Users.totpSecret] = null
                        it[Users.totpEnabled] = false
                    }
                    if (changed == 0) return@transaction false
                    ModerationAuditLog.insert {
                        it[ModerationAuditLog.actorId] = actorId
                        it[ModerationAuditLog.userId] = id
                        it[ModerationAuditLog.action] = "ADMIN_DISABLE_TOTP"
                        it[ModerationAuditLog.detail] = "admin disabled totp"
                        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                    }
                    true
                }
                if (!updated) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                call.respond(
                buildJsonObject {
put("status", "ok")
put("userId", id)
put("totpEnabled", false)
                }
            )
            }

            post("/users/bulk-force-logout") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(200)
                if (ids.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                }
                val skippedAdmins = mutableListOf<String>()
                val skippedMissing = mutableListOf<String>()
                val okIds = mutableListOf<String>()
                ids.forEach { id ->
                    if (AdminAccess.isAdmin(id) && id != actorId) {
                        skippedAdmins += id
                        return@forEach
                    }
                    if (authTokenRepo.rotateAccessTokenVersion(id) == 0L) {
                        skippedMissing += id
                        return@forEach
                    }
                    bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk force logout") }
                    okIds += id
                }
                transaction {
                    ModerationAuditLog.insert {
                        it[ModerationAuditLog.actorId] = actorId
                        it[ModerationAuditLog.userId] = actorId
                        it[ModerationAuditLog.action] = "ADMIN_BULK_FORCE_LOGOUT"
                        it[ModerationAuditLog.detail] = "ok=${okIds.size};skippedAdmins=${skippedAdmins.size}"
                        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("loggedOut", Json.parseToJsonElement(Json.encodeToString(okIds)))
put("skippedAdmins", Json.parseToJsonElement(Json.encodeToString(skippedAdmins)))
put("skippedMissing", Json.parseToJsonElement(Json.encodeToString(skippedMissing)))
put("count", okIds.size)
                }
            )
            }

            post("/users/bulk-ban") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, AdminDispositionPolicy.MAX_BAN_DAYS)
                val reasonCode = obj["reasonCode"]?.jsonPrimitive?.content.orEmpty().ifBlank { "BULK_BAN" }
                if (ids.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                }
                val until = System.currentTimeMillis() + days * 86_400_000L
                val skipped = mutableListOf<String>()
                val banned = mutableListOf<String>()
                // 8.48 修复 M3（bulk-ban）：批量存在性检查 + 单事务批量处置（此前逐 id 独立
                // 事务做「存在检查+UPDATE+审计」，最多 100 个事务）
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        // 9.128：已有更长封禁时保长——批量封禁按「追加 N 天」语义取 maxOf，
                        // 直接覆盖会把 30 天封禁缩成 1 天（此前与单用户 applyModerationRestriction 的
                        // maxOf 语义不一致）
                        val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                        if (row == null) {
                            skipped += id
                            return@forEach
                        }
                        val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.suspendedUntil], until)
                        Users.update({ Users.id eq id }) { it[Users.suspendedUntil] = effectiveUntil }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_BAN"
                            it[ModerationAuditLog.detail] = "days=$days;reason=$reasonCode".take(200)
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        banned += id
                    }
                }
                banned.forEach { id ->
                    authTokenRepo.rotateAccessTokenVersion(id)
                    bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk ban") }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("banned", Json.parseToJsonElement(Json.encodeToString(banned)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("count", banned.size)
                }
            )
            }

            
            post("/users/bulk-unban") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                }
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                // 8.48 修复 M3（bulk-unban）：批量存在性检查 + 单事务
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.suspendedUntil] = 0L }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_UNBAN"
                            it[ModerationAuditLog.detail] = "cleared suspension"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }


            post("/users/bulk-suspend-days") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, 365)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val until = System.currentTimeMillis() + days * 86_400_000L
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                // 8.48 修复 M3（bulk-suspend-days）：批量存在性检查 + 单事务
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        // 9.128：保长语义——不缩短既有更长封禁
                        val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                        if (row == null) {
                            skipped += id
                            return@forEach
                        }
                        val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.suspendedUntil], until)
                        Users.update({ Users.id eq id }) { it[Users.suspendedUntil] = effectiveUntil }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_SUSPEND_DAYS"
                            it[ModerationAuditLog.detail] = "days=$days;until=$effectiveUntil".take(200)
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                updated.forEach { id ->
                    authTokenRepo.rotateAccessTokenVersion(id)
                    bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk suspend days") }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("days", days)
put("count", updated.size)
                }
            )
            }

post("/users/bulk-message-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
                    .coerceIn(0, AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS)
                if (ids.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                }
                val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        // 9.128：保长语义——不缩短既有更长限制（days<=0 仍为解除）
                        val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                        if (row == null) {
                            skipped += id
                            return@forEach
                        }
                        val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.messageRestrictedUntil], until)
                        Users.update({ Users.id eq id }) { it[Users.messageRestrictedUntil] = effectiveUntil }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_MESSAGE_RESTRICT"
                            it[ModerationAuditLog.detail] = "days=$days;until=$effectiveUntil".take(200)
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("count", updated.size)
                }
            )
            }

            
            post("/users/bulk-message-unrestrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                }
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.messageRestrictedUntil] = 0L }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_MESSAGE_UNRESTRICT"
                            it[ModerationAuditLog.detail] = "cleared"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

get("/ai-usage-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
                // Metadata only — never export prompt/body
                val rows = transaction {
                    val resultRows = AiAuditLogs.selectAll()
                        .orderBy(
                            AiAuditLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            AiAuditLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .toList()
                    // 9.137：token 列已进 Table 单例（启动迁移补列），参数化 SQL 批量回填。
                    val tokenById: Map<String, Pair<Long?, Long?>> = if (resultRows.isEmpty()) emptyMap() else {
                        val ids = resultRows.map { it[AiAuditLogs.id] }
                        val placeholders = List(ids.size) { "?" }.joinToString(",")
                        exec(
                            "SELECT id, input_tokens, output_tokens FROM ai_audit_logs WHERE id IN ($placeholders)",
                            ids.map { VarCharColumnType() to it }
                        ) { rs ->
                            val m = mutableMapOf<String, Pair<Long?, Long?>>()
                            while (rs.next()) {
                                val input = rs.getLong(2)
                                val inputTokens: Long? = if (rs.wasNull()) null else input
                                val output = rs.getLong(3)
                                val outputTokens: Long? = if (rs.wasNull()) null else output
                                m[rs.getString(1)] = inputTokens to outputTokens
                            }
                            m
                        } ?: emptyMap()
                    }
                    resultRows.map { row ->
                        val tokens = tokenById[row[AiAuditLogs.id]]
                        listOf(
                            csvCell(row[AiAuditLogs.id]),
                            csvCell(row[AiAuditLogs.userId]),
                            csvCell(row[AiAuditLogs.feature].take(40)),
                            csvCell(row[AiAuditLogs.status]),
                            csvCell(row[AiAuditLogs.inputChars].toString()),
                            csvCell(row[AiAuditLogs.contextMessages].toString()),
                            csvCell((row[AiAuditLogs.durationMs] ?: 0L).toString()),
                            csvCell((row[AiAuditLogs.error] ?: "").replace("\n", " ").take(80)),
                            csvCell(row[AiAuditLogs.createdAt].toString()),
                            csvCell(tokens?.first?.toString() ?: ""),
                            csvCell(tokens?.second?.toString() ?: "")
                        ).joinToString(",")
                    }
                }
                val csv = buildString {
                    appendLine("id,userId,feature,status,inputChars,contextMessages,durationMs,error,createdAt,inputTokens,outputTokens")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "ai_usage_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-ai-usage.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }


            get("/push-tokens-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Privacy-safe: no full push token secret — prefix only
                val rows = transaction {
                    PushTokens.selectAll()
                        .orderBy(PushTokens.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            val tok = row[PushTokens.token]
                            val prefix = if (tok.length <= 12) tok.take(4) + "…" else tok.take(8) + "…" + tok.takeLast(4)
                            listOf(
                                csvCell(row[PushTokens.userId]),
                                csvCell(row[PushTokens.deviceId]),
                                csvCell(row[PushTokens.platform]),
                                csvCell(prefix),
                                csvCell(row[PushTokens.timezoneOffsetMinutes].toString()),
                                csvCell(row[PushTokens.updatedAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,deviceId,platform,tokenPrefix,timezoneOffsetMinutes,updatedAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "push_tokens_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-push-tokens.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }



            get("/bots/{id}/command-logs") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing bot id"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val bot = com.maodouchat.server.repository.BotRepository.get(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
                val logs = com.maodouchat.server.repository.BotRepository.listCommandLogs(id, limit, offset)
                call.respond(
                buildJsonObject {
put("botId", bot.id)
put("username", bot.username)
put("logs", Json.parseToJsonElement(Json.encodeToString(logs)))
put("count", logs.size)
                }
            )
            }
            get("/users-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                val rows = transaction {
                    Users.selectAll()
                        .orderBy(Users.lastSeen to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Users.id]),
                                csvCell(row[Users.name]),
                                csvCell(row[Users.email]),
                                csvCell(row[Users.status]),
                                csvCell(row[Users.isOnline].toString()),
                                csvCell(row[Users.isModerator].toString()),
                                csvCell(row[Users.suspendedUntil].toString()),
                                csvCell(row[Users.lastSeen].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,name,email,status,isOnline,isModerator,suspendedUntil,lastSeen")
                    rows.forEach { appendLine(it) }
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-users-${System.currentTimeMillis()}.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            
            
            
            get("/message-stats-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                // Aggregate by type only — never export message bodies (E2EE privacy).
                // 8.46 修复：原先把全表消息载入内存 + eachCount（百万级 OOM）；改为 SQL GROUP BY。
                val rows = transaction {
                    com.maodouchat.server.db.Messages
                        .slice(com.maodouchat.server.db.Messages.type, com.maodouchat.server.db.Messages.type.count())
                        .selectAll()
                        .groupBy(com.maodouchat.server.db.Messages.type)
                        .map { row ->
                            val type = row[com.maodouchat.server.db.Messages.type]
                            val count = row[com.maodouchat.server.db.Messages.type.count()]
                            listOf(csvCell(type), csvCell(count)).joinToString(",")
                        }
                        .sorted()
                }
                val total = transaction { com.maodouchat.server.db.Messages.selectAll().count() }
                val csv = buildString {
                    appendLine("type,count")
                    rows.forEach { appendLine(it) }
                    appendLine(listOf(csvCell("TOTAL"), csvCell(total)).joinToString(","))
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-message-stats-${System.currentTimeMillis()}.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/reports-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
                val rows = org.jetbrains.exposed.sql.transactions.transaction {
                    com.maodouchat.server.db.Reports.selectAll()
                        .orderBy(
                            com.maodouchat.server.db.Reports.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            com.maodouchat.server.db.Reports.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[com.maodouchat.server.db.Reports.id]),
                                csvCell(row[com.maodouchat.server.db.Reports.reporterId]),
                                csvCell(row[com.maodouchat.server.db.Reports.targetType]),
                                csvCell(row[com.maodouchat.server.db.Reports.targetId]),
                                csvCell(row[com.maodouchat.server.db.Reports.reason].take(200)),
                                csvCell(row[com.maodouchat.server.db.Reports.status]),
                                csvCell(row[com.maodouchat.server.db.Reports.createdAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,reporterId,targetType,targetId,reason,status,createdAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "reports_export", detail = "count=${rows.size}")
                call.response.header(
                    io.ktor.http.HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-reports.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/risk-events-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
                val rows = org.jetbrains.exposed.sql.transactions.transaction {
                    com.maodouchat.server.db.RiskEvents.selectAll()
                        .orderBy(
                            com.maodouchat.server.db.RiskEvents.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            com.maodouchat.server.db.RiskEvents.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[com.maodouchat.server.db.RiskEvents.id]),
                                csvCell(row[com.maodouchat.server.db.RiskEvents.userId]),
                                csvCell(row[com.maodouchat.server.db.RiskEvents.sourceValue]),
                                csvCell(row[com.maodouchat.server.db.RiskEvents.action]),
                                csvCell((row[com.maodouchat.server.db.RiskEvents.matched] ?: "").replace("\n", " ").take(200)),
                                csvCell(row[com.maodouchat.server.db.RiskEvents.needsReview].toString()),
                                csvCell(row[com.maodouchat.server.db.RiskEvents.createdAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,userId,source,action,matched,needsReview,createdAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "risk_events_export", detail = "count=${rows.size}")
                call.response.header(
                    io.ktor.http.HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-risk-events.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/online-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                // Privacy-safe: ids + presence only, no message bodies
                val online = try {
                    com.maodouchat.server.plugins.onlineUserIds()
                } catch (_: Exception) {
                    emptyList<String>()
                }
                val csv = buildString {
                    appendLine("userId,online")
                    online.forEach { appendLine(listOf(csvCell(it), csvCell(1)).joinToString(",")) }
                }
                recordAdminAudit(actorId = adminId, action = "online_export", detail = "count=${online.size}")
                call.response.header(
                    io.ktor.http.HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-online.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }



            
            get("/sessions-summary-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Privacy-safe: session counts per user, no token secrets
                val rows = transaction {
                    // Aggregate active refresh sessions by userId if table exposes userId
                    try {
                        // Fall back to listing users with online flag only when refresh table schema is private
                        val users = Users.selectAll()
                            .orderBy(Users.lastSeen to org.jetbrains.exposed.sql.SortOrder.DESC)
                            .limit(limit)
                            .toList()
                        // 8.48 修复 M7：批量统计活跃会话（此前逐用户 count → N+1）
                        val activeByUser = authTokenRepo.countActiveRefreshSessionsBatch(users.map { it[Users.id] })
                        users.map { row ->
                                val uid = row[Users.id]
                                listOf(
                                    csvCell(uid),
                                    csvCell(row[Users.name].take(40)),
                                    csvCell(row[Users.isOnline].toString()),
                                    csvCell((activeByUser[uid] ?: 0).toString()),
                                    csvCell(row[Users.lastSeen].toString())
                                ).joinToString(",")
                            }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val csv = buildString {
                    appendLine("userId,name,isOnline,activeRefreshSessions,lastSeen")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "sessions_summary_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-sessions-summary.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

get("/polls-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
                val rows = transaction {
                    val polls = GroupPolls.selectAll()
                        .orderBy(
                            GroupPolls.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            GroupPolls.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .toList()
                    // 8.48 修复 H6：批量 count（此前逐投票查询 → limit 1 万次查询）
                    val pollIds = polls.map { it[GroupPolls.id] }
                    val votesByPoll = if (pollIds.isEmpty()) emptyMap() else
                        GroupPollVotes
                            .slice(GroupPollVotes.pollId, GroupPollVotes.userId.count())
                            .selectAll()
                            .where { GroupPollVotes.pollId inList pollIds }
                            .groupBy(GroupPollVotes.pollId)
                            .associate { it[GroupPollVotes.pollId] to it[GroupPollVotes.userId.count()].toLong() }
                    polls.map { row ->
                            val id = row[GroupPolls.id]
                            val votes = votesByPoll[id] ?: 0L
                            listOf(
                                csvCell(id),
                                csvCell(row[GroupPolls.chatId]),
                                csvCell(row[GroupPolls.creatorId]),
                                csvCell(row[GroupPolls.question].take(120)),
                                csvCell(row[GroupPolls.multi].toString()),
                                csvCell(row[GroupPolls.anonymous].toString()),
                                csvCell(row[GroupPolls.closed].toString()),
                                csvCell(votes.toString()),
                                csvCell(row[GroupPolls.createdAt].toString()),
                                csvCell((row[GroupPolls.closesAt] ?: 0L).toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,chatId,creatorId,question,multi,anonymous,closed,voteRows,createdAt,closesAt")
                    rows.forEach { appendLine(it) }
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-polls-${System.currentTimeMillis()}.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            get("/moderation-audit-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
                // Audit metadata only — no message bodies
                val rows = transaction {
                    ModerationAuditLog.selectAll()
                        .orderBy(
                            ModerationAuditLog.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            ModerationAuditLog.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[ModerationAuditLog.id]),
                                csvCell(row[ModerationAuditLog.actorId].orEmpty()),
                                csvCell(row[ModerationAuditLog.userId].orEmpty()),
                                csvCell(row[ModerationAuditLog.action].take(40)),
                                csvCell(row[ModerationAuditLog.detail].orEmpty().replace("\n", " ").take(160)),
                                csvCell(row[ModerationAuditLog.createdAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,actorId,userId,action,detail,createdAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "moderation_audit_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-moderation-audit.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }


            get("/bot-command-stats-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Command names only — no message bodies
                val rows = transaction {
                    BotCommandLogs.selectAll()
                        .orderBy(
                            BotCommandLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            BotCommandLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[BotCommandLogs.id]),
                                csvCell(row[BotCommandLogs.botId]),
                                csvCell((row[BotCommandLogs.chatId] ?: "").take(40)),
                                csvCell((row[BotCommandLogs.userId] ?: "").take(40)),
                                csvCell(row[BotCommandLogs.command].take(80)),
                                csvCell(row[BotCommandLogs.createdAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,botId,chatId,userId,command,createdAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "bot_command_stats_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-bot-command-stats.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            post("/users/bulk-post-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
                    .coerceIn(0, AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        // 9.128：保长语义——不缩短既有更长限制（days<=0 仍为解除）
                        val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                        if (row == null) {
                            skipped += id
                            return@forEach
                        }
                        val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.postRestrictedUntil], until)
                        Users.update({ Users.id eq id }) { it[Users.postRestrictedUntil] = effectiveUntil }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_POST_RESTRICT"
                            it[ModerationAuditLog.detail] = "days=$days;until=$effectiveUntil".take(200)
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("days", days)
put("count", updated.size)
                }
            )
            }


            post("/users/bulk-post-unrestrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.postRestrictedUntil] = 0L }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_POST_UNRESTRICT"
                            it[ModerationAuditLog.detail] = "cleared"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-set-message-restrict-until") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                val until = (obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: obj["untilMs"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: 0L).coerceAtLeast(0L)
                // 8.37：与单用户端点一致的时间合法性校验（此前只 coerceAtLeast(0)，
                // 过去时间戳被静默写成已过期限制，Long.MAX_VALUE 绕过 10 年上限）
                val now = System.currentTimeMillis()
                if (until > now + com.maodouchat.server.service.AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L ||
                    (until in 1..now)
                ) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁发消息截止时间无效"))
                }
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.messageRestrictedUntil] = until }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_SET_MSG_RESTRICT_UNTIL"
                            it[ModerationAuditLog.detail] = "until=$until".take(200)
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("count", updated.size)
                }
            )
            }


            get("/friends-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Friendship graph metadata only — no message bodies
                val rows = transaction {
                    Friendships.selectAll()
                        .orderBy(
                            Friendships.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            Friendships.userLowId to org.jetbrains.exposed.sql.SortOrder.DESC,
                            Friendships.userHighId to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Friendships.userLowId]),
                                csvCell(row[Friendships.userHighId]),
                                csvCell(row[Friendships.createdAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userLowId,userHighId,createdAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "friends_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-friends.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }


            get("/reports-meta-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Report metadata only — no message bodies / E2EE plaintext
                val rows = transaction {
                    Reports.selectAll()
                        .orderBy(
                            Reports.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                            Reports.id to org.jetbrains.exposed.sql.SortOrder.DESC
                        )
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Reports.id]),
                                csvCell(row[Reports.reporterId]),
                                csvCell(row[Reports.targetType]),
                                csvCell(row[Reports.targetId]),
                                csvCell((row[Reports.chatId] ?: "")),
                                csvCell(row[Reports.reason].take(60)),
                                csvCell(row[Reports.status]),
                                csvCell((row[Reports.actionTaken] ?: "")),
                                csvCell(row[Reports.createdAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,reporterId,targetType,targetId,chatId,reason,status,actionTaken,createdAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "reports_meta_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-reports-meta.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            get("/blocks-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Block edges only — no message bodies
                val rows = transaction {
                    BlockedUsers.selectAll()
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[BlockedUsers.blockerId]),
                                csvCell(row[BlockedUsers.blockedId])
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("blockerId,blockedId")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "blocks_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-blocks.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            get("/chat-settings-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Per-user chat settings metadata only — no message bodies
                val rows = transaction {
                    ChatUserSettings.selectAll()
                        .orderBy(ChatUserSettings.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[ChatUserSettings.userId]),
                                csvCell(row[ChatUserSettings.chatId]),
                                csvCell(row[ChatUserSettings.pinnedAt].toString()),
                                csvCell(row[ChatUserSettings.notificationsMuted].toString()),
                                csvCell(row[ChatUserSettings.archived].toString()),
                                csvCell(row[ChatUserSettings.markedUnread].toString()),
                                csvCell(row[ChatUserSettings.updatedAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,chatId,pinnedAt,notificationsMuted,archived,markedUnread,updatedAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "chat_settings_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-chat-settings.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            get("/disappearing-chats-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Chat disappearing timer metadata only — no message bodies
                val rows = transaction {
                    Chats.selectAll()
                        .orderBy(Chats.memberRevision to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit)
                        .mapNotNull { row ->
                            val seconds = row[Chats.disappearingMessageSeconds]
                            if (seconds <= 0) return@mapNotNull null
                            listOf(
                                csvCell(row[Chats.id]),
                                csvCell(row[Chats.isGroup].toString()),
                                csvCell((row[Chats.groupName] ?: "").take(80)),
                                csvCell(seconds.toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("chatId,isGroup,groupName,disappearingSeconds")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "disappearing_chats_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-disappearing-chats.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            get("/muted-chats-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Muted chat settings metadata only — no message bodies
                val rows = transaction {
                    ChatUserSettings.selectAll()
                        .orderBy(ChatUserSettings.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit * 2)
                        .mapNotNull { row ->
                            if (!row[ChatUserSettings.notificationsMuted]) return@mapNotNull null
                            listOf(
                                csvCell(row[ChatUserSettings.userId]),
                                csvCell(row[ChatUserSettings.chatId]),
                                csvCell(row[ChatUserSettings.notificationsMuted].toString()),
                                csvCell(row[ChatUserSettings.updatedAt].toString())
                            ).joinToString(",")
                        }
                        .take(limit)
                }
                val csv = buildString {
                    appendLine("userId,chatId,notificationsMuted,updatedAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "muted_chats_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-muted-chats.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            
            
            





            get("/ai-feature-flags-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val csv = buildString {
                    appendLine("key,value")
                    appendLine("ai_enabled," + RuntimeConfigService.isAiEnabled())
                    appendLine("ai_summary_enabled," + RuntimeConfigService.isAiSummaryEnabled())
                    appendLine("ai_rewrite_enabled," + RuntimeConfigService.isAiRewriteEnabled())
                    appendLine("ai_suggest_replies_enabled," + RuntimeConfigService.isAiSuggestRepliesEnabled())
                    appendLine("ai_transcribe_enabled," + RuntimeConfigService.isAiTranscribeEnabled())
                    appendLine("ai_analyze_image_enabled," + RuntimeConfigService.isAiAnalyzeImageEnabled())
                    appendLine("ai_group_assistant_enabled," + RuntimeConfigService.isAiGroupAssistantEnabled())
                    appendLine("ai_analyze_file_enabled," + RuntimeConfigService.isAiAnalyzeFileEnabled())
                    appendLine("ai_semantic_search_enabled," + RuntimeConfigService.isAiSemanticSearchEnabled())
                    appendLine("gif_send_enabled," + RuntimeConfigService.isGifSendEnabled())
                    appendLine("blind_watermark_enabled," + RuntimeConfigService.isBlindWatermarkEnabled())
                    appendLine("voice_call_enabled," + RuntimeConfigService.isVoiceCallEnabled())
                    appendLine("video_call_enabled," + RuntimeConfigService.isVideoCallEnabled())
                    appendLine("chat_wallpaper_enabled," + RuntimeConfigService.isChatWallpaperEnabled())
                    appendLine("chat_font_scale_enabled," + RuntimeConfigService.isChatFontScaleEnabled())
                    appendLine("unread_priority_enabled," + RuntimeConfigService.isUnreadPriorityEnabled())
                    appendLine("ringtone_enabled," + RuntimeConfigService.isRingtoneEnabled())
                    appendLine("notification_sound_enabled," + RuntimeConfigService.isNotificationSoundEnabled())
                    appendLine("notification_preview_enabled," + RuntimeConfigService.isNotificationPreviewEnabled())
                    appendLine("push_notifications_enabled," + RuntimeConfigService.isPushNotificationsEnabled())
                    appendLine("task_reminders_enabled," + RuntimeConfigService.isTaskRemindersEnabled())
                    appendLine("dnd_enabled," + RuntimeConfigService.isDndEnabled())
                    appendLine("offline_ai_enabled," + RuntimeConfigService.isOfflineAiEnabled())
                    appendLine("in_app_sounds_enabled," + RuntimeConfigService.isInAppSoundsEnabled())
                    appendLine("haptics_enabled," + RuntimeConfigService.isHapticsEnabled())
                    appendLine("chat_animations_enabled," + RuntimeConfigService.isChatAnimationsEnabled())
                    appendLine("nav_transitions_enabled," + RuntimeConfigService.isNavTransitionsEnabled())
                    appendLine("screenshot_detect_enabled," + RuntimeConfigService.isScreenshotDetectEnabled())
                    appendLine("recents_exclusion_enabled," + RuntimeConfigService.isRecentsExclusionEnabled())
                    appendLine("secret_copy_block_enabled," + RuntimeConfigService.isSecretCopyBlockEnabled())
                    appendLine("secret_media_export_block_enabled," + RuntimeConfigService.isSecretMediaExportBlockEnabled())
                    appendLine("secret_forward_block_enabled," + RuntimeConfigService.isSecretForwardBlockEnabled())
                    appendLine("secret_chat_export_block_enabled," + RuntimeConfigService.isSecretChatExportBlockEnabled())
                    appendLine("visible_watermark_enabled," + RuntimeConfigService.isVisibleWatermarkEnabled())
                    appendLine("secret_auto_disappear_enabled," + RuntimeConfigService.isSecretAutoDisappearEnabled())
                    appendLine("secret_link_preview_block_enabled," + RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
                    appendLine("secret_external_link_block_enabled," + RuntimeConfigService.isSecretExternalLinkBlockEnabled())
                    appendLine("secret_notif_preview_block_enabled," + RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
                    appendLine("secret_list_preview_block_enabled," + RuntimeConfigService.isSecretListPreviewBlockEnabled())
                    appendLine("secret_reaction_block_enabled," + RuntimeConfigService.isSecretReactionBlockEnabled())
                    appendLine("secret_star_block_enabled," + RuntimeConfigService.isSecretStarBlockEnabled())
                    appendLine("secret_typing_block_enabled," + RuntimeConfigService.isSecretTypingBlockEnabled())
                    appendLine("secret_read_receipt_block_enabled," + RuntimeConfigService.isSecretReadReceiptBlockEnabled())
                    appendLine("secret_presence_block_enabled," + RuntimeConfigService.isSecretPresenceBlockEnabled())
                    appendLine("secret_last_seen_block_enabled," + RuntimeConfigService.isSecretLastSeenBlockEnabled())
                    appendLine("ai_translate_enabled," + RuntimeConfigService.isAiTranslateEnabled())
                    appendLine("image_send_enabled," + RuntimeConfigService.isImageSendEnabled())
                    appendLine("video_send_enabled," + RuntimeConfigService.isVideoSendEnabled())
                }
                recordAdminAudit(actorId = adminId, action = "ai_feature_flags_export", detail = "runtime flags")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat-ai-feature-flags.csv\"")
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/online-presence-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                val rows = transaction {
                    Users.selectAll()
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Users.id]),
                                csvCell(row[Users.isOnline].toString()),
                                csvCell(row[Users.lastSeen].toString()),
                                csvCell(row[Users.showOnline].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,isOnline,lastSeen,showOnline")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "online_presence_export", detail = "count=${rows.size}")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat-online-presence.csv\"")
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/privacy-flags-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                val rows = transaction {
                    Users.selectAll()
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Users.id]),
                                csvCell(row[Users.showOnline].toString()),
                                csvCell(row[Users.showStatus].toString()),
                                csvCell(row[Users.searchable].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,showOnline,showStatus,searchable")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "privacy_flags_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-privacy-flags.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/identity-users-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Identity discoverability metadata only — no secrets / bodies
                val rows = transaction {
                    Users.selectAll()
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Users.id]),
                                csvCell(row[Users.searchable].toString()),
                                csvCell(row[Users.showOnline].toString()),
                                csvCell(row[Users.totpEnabled].toString()),
                                csvCell(row[Users.email].take(3) + "***")
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,searchable,showOnline,totpEnabled,emailHint")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "identity_users_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-identity-users.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/totp-users-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // TOTP status only — no secrets / E2EE bodies
                val rows = transaction {
                    Users.selectAll()
                        .where { Users.totpEnabled eq true }
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Users.id]),
                                csvCell(row[Users.totpEnabled].toString()),
                                csvCell(row[Users.email].take(3) + "***")
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,totpEnabled,emailHint")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "totp_users_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-totp-users.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/group-invites-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Invite metadata only — no message bodies
                val rows = transaction {
                    Chats.selectAll()
                        .where { Chats.groupInviteToken.isNotNull() }
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Chats.id]),
                                csvCell((row[Chats.groupInviteToken] ?: "").take(12)),
                                csvCell(row[Chats.groupInviteExpiresAt].toString()),
                                csvCell(row[Chats.groupInviteMaxUses].toString()),
                                csvCell(row[Chats.groupInviteUseCount].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("chatId,tokenPrefix,expiresAt,maxUses,useCount")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "group_invites_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-group-invites.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

get("/restricted-users-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                val now = System.currentTimeMillis()
                val rows = transaction {
                    Users.selectAll()
                        .where {
                            (Users.messageRestrictedUntil greater now) or
                                (Users.postRestrictedUntil greater now) or
                                (Users.suspendedUntil greater now)
                        }
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[Users.id]),
                                csvCell(row[Users.messageRestrictedUntil].toString()),
                                csvCell(row[Users.postRestrictedUntil].toString()),
                                csvCell(row[Users.suspendedUntil].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("userId,messageRestrictedUntil,postRestrictedUntil,suspendedUntil")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "restricted_users_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-restricted-users.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

get("/poll-votes-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                val rows = transaction {
                    GroupPollVotes.selectAll()
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[GroupPollVotes.pollId]),
                                csvCell(row[GroupPollVotes.userId]),
                                csvCell(row[GroupPollVotes.optionIndex].toString()),
                                csvCell(row[GroupPollVotes.votedAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("pollId,userId,optionIndex,votedAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "poll_votes_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-poll-votes.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

get("/pinned-messages-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
                // Pinned message metadata only — no message bodies / E2EE plaintext
                val rows = transaction {
                    PinnedMessages.selectAll()
                        .orderBy(PinnedMessages.pinnedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            listOf(
                                csvCell(row[PinnedMessages.chatId]),
                                csvCell(row[PinnedMessages.messageId]),
                                csvCell(row[PinnedMessages.pinnedBy]),
                                csvCell(row[PinnedMessages.pinnedAt].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("chatId,messageId,pinnedBy,pinnedAt")
                    rows.forEach { appendLine(it) }
                }
                recordAdminAudit(actorId = adminId, action = "pinned_messages_export", detail = "count=${rows.size}")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-pinned-messages.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            
            
            





            post("/users/bulk-set-searchable-false") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.searchable] = false }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_set_searchable_false", detail = "count=${updated.size}")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

            post("/users/bulk-set-searchable-true") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.searchable] = true }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_set_searchable_true", detail = "count=${updated.size}")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

            post("/users/bulk-set-show-status") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val showStatus = when (val raw = obj["showStatus"]?.jsonPrimitive?.content?.lowercase()) {
                    "false", "0", "no", "off" -> false
                    "true", "1", "yes", "on" -> true
                    // 9.131：缺字段/拼写错误不得静默默认 true（隐私开关被反向打开）
                    else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("showStatus must be a boolean"))
                }
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.showStatus] = showStatus }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_set_show_status", detail = "count=${updated.size};showStatus=$showStatus")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("showStatus", showStatus)
                }
            )
            }

            post("/users/bulk-set-show-online") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val showOnline = when (val raw = obj["showOnline"]?.jsonPrimitive?.content?.lowercase()) {
                    "false", "0", "no", "off" -> false
                    "true", "1", "yes", "on" -> true
                    // 9.131：缺字段/拼写错误不得静默默认 true
                    else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("showOnline must be a boolean"))
                }
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) {
                            it[Users.showOnline] = showOnline
                        }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_set_show_online", detail = "count=${updated.size};showOnline=$showOnline")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("showOnline", showOnline)
                }
            )
            }

            post("/users/bulk-set-searchable") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val searchable = when (val raw = obj["searchable"]?.jsonPrimitive?.content?.lowercase()) {
                    "false", "0", "no", "off" -> false
                    "true", "1", "yes", "on" -> true
                    // 9.131：缺字段/拼写错误不得静默默认 true（把用户批量设成可被搜索）
                    else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("searchable must be a boolean"))
                }
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) {
                            it[Users.searchable] = searchable
                        }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_set_searchable", detail = "count=${updated.size};searchable=$searchable")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("searchable", searchable)
                }
            )
            }

            post("/users/bulk-disable-totp") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if ((AdminAccess.isAdmin(id) && id != actorId) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) {
                            it[Users.totpSecret] = null
                            it[Users.totpEnabled] = false
                        }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_disable_totp", detail = "count=${updated.size}")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

            post("/chats/bulk-clear-invite-tokens") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["chatIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Chats.select(Chats.id).where { Chats.id inList ids }.map { it[Chats.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Chats.update({ Chats.id eq id }) {
                            it[Chats.groupInviteToken] = null
                            it[Chats.groupInviteExpiresAt] = 0L
                            it[Chats.groupInviteMaxUses] = 0
                            it[Chats.groupInviteUseCount] = 0
                        }
                        updated += id
                    }
                }
                recordAdminAudit(actorId = actorId, action = "bulk_clear_invite_tokens", detail = "count=${updated.size}")
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-set-suspend-until") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val until = obj["suspendedUntil"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: 0L
                val now = System.currentTimeMillis()
                if (until < 0 || until > now + MAX_ADMIN_SUSPEND_MS || (until in 1..now)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("suspendedUntil invalid"))
                }
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '	').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val shouldInvalidate = until > now
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) {
                            it[Users.suspendedUntil] = until
                        }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_SET_SUSPEND_UNTIL"
                            it[ModerationAuditLog.detail] = "until=$until"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                if (shouldInvalidate) {
                    updated.forEach { id ->
                        authTokenRepo.rotateAccessTokenVersion(id)
                        bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk suspend until") }
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
put("suspendedUntil", until)
                }
            )
            }

post("/users/bulk-clear-all-restrictions") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) {
                            it[Users.messageRestrictedUntil] = 0L
                            it[Users.postRestrictedUntil] = 0L
                            it[Users.suspendedUntil] = 0L
                        }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_ALL_RESTRICTIONS"
                            it[ModerationAuditLog.detail] = "cleared msg+post+suspend"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-clear-message-and-post-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) {
                            it[Users.messageRestrictedUntil] = 0L
                            it[Users.postRestrictedUntil] = 0L
                        }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_MSG_AND_POST_RESTRICT"
                            it[ModerationAuditLog.detail] = "cleared"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-force-token-bump") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val bumped = mutableListOf<Triple<String, Long, Long>>()
                ids.forEach { id ->
                    if (id == actorId || AdminAccess.isAdmin(id)) {
                        skipped += id
                        return@forEach
                    }
                    val next = authTokenRepo.rotateAccessTokenVersion(id)
                    val ok = next > 0L
                    if (ok) {
                        bumped += Triple(id, next, System.currentTimeMillis())
                    }
                    if (ok) updated += id else skipped += id
                }
                if (bumped.isNotEmpty()) {
                    transaction {
                        ModerationAuditLog.batchInsert(bumped) { entry ->
                            this[ModerationAuditLog.userId] = entry.first
                            this[ModerationAuditLog.action] = "ADMIN_BULK_TOKEN_BUMP"
                            this[ModerationAuditLog.detail] = "version=${entry.second}"
                            this[ModerationAuditLog.actorId] = actorId
                            this[ModerationAuditLog.createdAt] = entry.third
                        }
                    }
                    bumped.forEach { (id, _, _) ->
                        try {
                            disconnectUserSessions(id, "admin bulk token bump")
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                        }
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-clear-suspend") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.suspendedUntil] = 0L }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_SUSPEND"
                            it[ModerationAuditLog.detail] = "cleared"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-clear-message-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.messageRestrictedUntil] = 0L }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_MSG_RESTRICT"
                            it[ModerationAuditLog.detail] = "cleared"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

post("/users/bulk-message-restrict-days") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                // 9.131：上限改用策略常量——此前硬编码 3650 天（10 年）绕过
                // AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS(90) 的处置上限
                val days = (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
                    .coerceIn(1, AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS)
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        // 9.128：保长语义——不缩短既有更长限制
                        val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                        if (row == null) {
                            skipped += id
                            return@forEach
                        }
                        val effectiveUntil = if (until <= 0L) 0L else maxOf(row[Users.messageRestrictedUntil], until)
                        Users.update({ Users.id eq id }) { it[Users.messageRestrictedUntil] = effectiveUntil }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_MSG_RESTRICT_DAYS"
                            it[ModerationAuditLog.detail] = "days=$days until=$effectiveUntil".take(200)
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("until", until)
put("days", days)
put("count", updated.size)
                }
            )
            }

post("/users/bulk-clear-post-restrict") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val rawIds = obj["userIds"]
                val ids = when {
                    rawIds == null -> emptyList()
                    rawIds is kotlinx.serialization.json.JsonArray -> rawIds.mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }
                    else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
                }.map { it.take(64) }.distinct().take(100)
                if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
                val updated = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val existing = transaction {
                    Users.select(Users.id).where { Users.id inList ids }.map { it[Users.id] }.toSet()
                }
                transaction {
                    ids.forEach { id ->
                        if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                            skipped += id
                            return@forEach
                        }
                        Users.update({ Users.id eq id }) { it[Users.postRestrictedUntil] = 0L }
                        ModerationAuditLog.insert {
                            it[ModerationAuditLog.userId] = id
                            it[ModerationAuditLog.action] = "ADMIN_BULK_CLEAR_POST_RESTRICT"
                            it[ModerationAuditLog.detail] = "cleared"
                            it[ModerationAuditLog.actorId] = actorId
                            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                        }
                        updated += id
                    }
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("updated", Json.parseToJsonElement(Json.encodeToString(updated)))
put("skipped", Json.parseToJsonElement(Json.encodeToString(skipped)))
put("count", updated.size)
                }
            )
            }

            get("/chats-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)
                val rows = transaction {
                    val chats = Chats.selectAll()
                        .orderBy(Chats.memberRevision to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(limit)
                        .toList()
                    // 8.48 修复 H2：GROUP BY 批量成员计数（此前逐会话 count → 最多 1 万次查询）
                    val chatIds = chats.map { it[Chats.id] }
                    val membersByChat = if (chatIds.isEmpty()) emptyMap() else
                        ChatParticipants
                            .slice(ChatParticipants.chatId, ChatParticipants.userId.count())
                            .selectAll()
                            .where { ChatParticipants.chatId inList chatIds }
                            .groupBy(ChatParticipants.chatId)
                            .associate { it[ChatParticipants.chatId] to it[ChatParticipants.userId.count()].toLong() }
                    chats.map { row ->
                            val id = row[Chats.id]
                            val isGroup = row[Chats.isGroup]
                            val name = (row[Chats.groupName] ?: "").take(80)
                            val members = membersByChat[id] ?: 0L
                            listOf(
                                csvCell(id),
                                csvCell(if (isGroup) "group" else "direct"),
                                csvCell(name),
                                csvCell(members.toString()),
                                csvCell(row[Chats.memberRevision].toString()),
                                csvCell(row[Chats.disappearingMessageSeconds].toString())
                            ).joinToString(",")
                        }
                }
                val csv = buildString {
                    appendLine("id,type,title,memberCount,memberRevision,disappearingSeconds")
                    rows.forEach { appendLine(it) }
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"maodouchat-chats-${System.currentTimeMillis()}.csv\""
                )
                call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
            }

            get("/runtime-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                val actorId = call.principal<JWTPrincipal>()!!.payload.subject
                recordAdminAudit(actorId, "ADMIN_RUNTIME_EXPORT", "settings snapshot")
                call.respond(
                buildJsonObject {
put("generatedAt", System.currentTimeMillis())
put("settings", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.all())))
put("defaults", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.defaults())))
put("security", buildJsonObject {
put("sealedSenderEnabled", RuntimeConfigService.isSealedSenderEnabled())
put("aiEnabled", RuntimeConfigService.isAiEnabled())
put("botsAllowed", RuntimeConfigService.isBotsAllowed())
put("secretChatRequired", RuntimeConfigService.isSecretChatRequired())
put("captureAlertEnabled", RuntimeConfigService.isCaptureAlertEnabled())
put("pqxdhPreview", RuntimeConfigService.isPqxdhPreviewEnabled())
put("minAppVersion", RuntimeConfigService.minAppVersion())
put("maxBotsPerUser", RuntimeConfigService.maxBotsPerUser())
put("ipBlocklistCount", RuntimeConfigService.ipBlocklist().size)
put("maxMessagePerMin", RuntimeConfigService.maxMessagePerMinute())
})
                }
            )
            }

post("/watermark/extract") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_WATERMARK_BODY_CHARS) }.getOrNull().orEmpty()
                if (bodyText.length > MAX_ADMIN_WATERMARK_BODY_CHARS) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("请求体过大"))
                }
                val imageB64 = runCatching {
                    val el = adminJson.parseToJsonElement(bodyText)
                    el.jsonObject["imageBase64"]?.jsonPrimitive?.content.orEmpty()
                }.getOrDefault("")
                if (imageB64.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("imageBase64_required"))
                }
                val result = com.maodouchat.server.watermark.AdminWatermarkExtractor.extractFromBase64(imageB64)
                recordAdminAudit(
                    actorId = adminId,
                    action = "watermark_extract",
                    detail = "found=${result.found};msg=${result.message};hex=${result.payloadHex.orEmpty().take(24)}"
                )
                call.respond(
                    WatermarkExtractResponse(
                        found = result.found,
                        payloadHex = result.payloadHex.orEmpty(),
                        width = result.width,
                        height = result.height,
                        message = result.message,
                        notes = "payload is FNV-1a48 of userId|chatId|deviceHint; visible tiles also embed wall-clock time"
                    )
                )
            }

            get("/watermark/self-test") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val adminId = call.principal<JWTPrincipal>()!!.payload.subject
                val sample = com.maodouchat.server.watermark.AdminWatermarkExtractor.embedDemoPngBase64(
                    userId = adminId,
                    chatId = "self-test-chat",
                    deviceHint = "admin-console"
                )
                val extracted = com.maodouchat.server.watermark.AdminWatermarkExtractor.extractFromBase64(sample)
                call.respond(
                    WatermarkSelfTestResponse(
                        samplePngBase64 = sample,
                        found = extracted.found,
                        payloadHex = extracted.payloadHex.orEmpty(),
                        message = extracted.message
                    )
                )
            }

            // ─── Dashboard HTML ──────────────
            get("/dashboard.html") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                call.respondAdminDashboardPage()
            }
            }
        }
    }
}
/** 独立管理后台只允许 MASTER_ADMINS；内容审核员继续使用受限审核 API。 */
private suspend fun io.ktor.server.application.ApplicationCall.isAdminUser(): Boolean {
    val principal = principal<JWTPrincipal>() ?: return false
    val userId = principal.payload.subject
    return AdminAccess.isAdmin(userId)
}

private suspend fun bestEffortAdminDisconnect(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
    }
}

private fun recordAdminAudit(actorId: String, action: String, detail: String) {
    transaction {
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = actorId
            it[ModerationAuditLog.action] = action.take(40)
            it[ModerationAuditLog.detail] = detail.take(500)
            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
        }
    }
}

@Serializable
data class WatermarkExtractResponse(
    val found: Boolean,
    val payloadHex: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val message: String = "",
    val notes: String = ""
)

@Serializable
data class WatermarkSelfTestResponse(
    val samplePngBase64: String,
    val found: Boolean,
    val payloadHex: String = "",
    val message: String = ""
)

@Serializable
data class UpdateUserStatusRequest(
    val bannedUntil: Long? = null,
    val note: String? = null,
    val reasonCode: String? = null
)

@Serializable
data class UpdatePostRestrictionRequest(
    val postRestrictedUntil: Long? = null,
    val note: String? = null,
    val reasonCode: String? = null
)

@Serializable
data class UpdateMessageRestrictionRequest(
    val messageRestrictedUntil: Long? = null,
    val note: String? = null,
    val reasonCode: String? = null
)

@Serializable
data class DispositionReasonDto(
    val code: String,
    val labelZh: String,
    val defaultDays: Int,
    val requiresCustomNote: Boolean = false
)

@Serializable
data class MuteReasonDto(
    val code: String,
    val labelZh: String,
    val durationHours: Int,
    val requiresCustomNote: Boolean = false
)

@Serializable
data class DispositionTemplatesResponse(
    val banReasons: List<DispositionReasonDto>,
    val muteReasons: List<MuteReasonDto> = emptyList(),
    val postRestrictReasons: List<DispositionReasonDto> = emptyList(),
    val messageRestrictReasons: List<DispositionReasonDto> = emptyList(),
    val unbanReasonCode: String,
    val unmuteReasonCode: String = "unmute",
    val unrestrictPostsReasonCode: String = "unrestrict_posts",
    val unrestrictMessagesReasonCode: String = "unrestrict_messages",
    val appealNoticeZh: String,
    val maxBanDays: Int,
    val maxPostRestrictDays: Int = AdminDispositionPolicy.MAX_POST_RESTRICT_DAYS,
    val maxMessageRestrictDays: Int = AdminDispositionPolicy.MAX_MESSAGE_RESTRICT_DAYS
)

@Serializable
data class AdminAuditLogResponse(
    val id: String,
    val actorId: String? = null,
    val targetUserId: String? = null,
    val action: String,
    val detail: String? = null,
    val createdAt: Long
)

private const val MAX_ADMIN_SUSPEND_MS = 10L * 365L * 24L * 60L * 60L * 1_000L
private const val MAX_ADMIN_JSON_BODY_CHARS = 80 * 1024
private const val MAX_ADMIN_WATERMARK_BODY_CHARS = 4 * 1024 * 1024
private const val ADMIN_SESSION_ATTEMPT_WINDOW_SECONDS = 5 * 60
private const val MAX_ADMIN_SESSION_ATTEMPTS = 5
private const val MAX_ADMIN_SESSION_ATTEMPT_BUCKETS = 10_000
private val adminJson = Json { ignoreUnknownKeys = true }
private val adminSessionAttemptLimiter = AdminSessionAttemptLimiter()

internal class AdminSessionAttemptLimiter {
    private val delegate = BoundedRateLimiter(
        maxBuckets = MAX_ADMIN_SESSION_ATTEMPT_BUCKETS,
        windowMs = ADMIN_SESSION_ATTEMPT_WINDOW_SECONDS * 1_000L,
    )

    fun acquire(userId: String, now: Long = System.currentTimeMillis()): Boolean =
        delegate.acquire(userId, maxPerMinute = MAX_ADMIN_SESSION_ATTEMPTS, now = now)

    fun reset(userId: String) {
        delegate.reset(userId)
    }
}

private suspend inline fun <reified T> ApplicationCall.receiveAdminJson(): T? {
    val body = receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) ?: return null
    return runCatching { adminJson.decodeFromString<T>(body) }.getOrNull()
}

private suspend fun ApplicationCall.respondAdminDashboardPage() {
    response.headers.append(HttpHeaders.CacheControl, "no-store, max-age=0")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append(
        "Content-Security-Policy",
        // 8.47 修复：admin.js 广泛使用内联 style="..."——此前 style-src 禁 unsafe-inline
        // 导致管理后台布局损坏。样式内联已放开，但内联脚本已全部移除（事件改为
        // .onclick / addEventListener / data-action 委托），保留 script-src 'self'。
        "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; " +
            "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    )
    respondText(adminDashboardHtml, contentType = io.ktor.http.ContentType.Text.Html)
}

private suspend fun ApplicationCall.respondAdminAsset(content: String, contentType: io.ktor.http.ContentType) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache, max-age=0, must-revalidate")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
    respondText(content, contentType = contentType)
}

/** Escape LIKE pattern special characters (%, _, \) so user input is treated literally. */
private fun escapeLikePattern(input: String): String =
    input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun csvCell(value: Any?): String {
    val raw = value?.toString() ?: ""
    // 公式注入防护须按「去除前导空白后的首字符」判定：Excel 会忽略前导空白/制表符
    // 求值单元格，此前仅查原始首字符，`" =CMD()"` 这类以空格开头的载荷仍会执行。
    val formulaSafe = if (raw.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$raw" else raw
    return "\"${formulaSafe.replace("\"", "\"\"")}\""
}

private fun org.jetbrains.exposed.sql.ResultRow.toUserAdminResponse(): UserAdminResponse = UserAdminResponse(
    id = this[Users.id],
    name = this[Users.name],
    email = this[Users.email],
    isModerator = this[Users.isModerator],
    lastActiveAt = this[Users.lastSeen],
    suspendedUntil = this[Users.suspendedUntil],
    postRestrictedUntil = this[Users.postRestrictedUntil],
    messageRestrictedUntil = this[Users.messageRestrictedUntil],
    deletedAt = this[Users.deletedAt]
)

private fun org.jetbrains.exposed.sql.ResultRow.toPostAdminResponse(authorName: String): PostAdminResponse = PostAdminResponse(
    id = this[Posts.id],
    authorId = this[Posts.authorId],
    authorName = authorName,
    content = this[Posts.content],
    status = this[Posts.status],
    createdAt = this[Posts.createdAt]
)

private val adminDashboardHtml: String by lazy { loadAdminResource("admin/admin.html") }
private val adminDashboardCss: String by lazy { loadAdminResource("admin/admin.css") }
private val adminDashboardThemeJs: String by lazy { loadAdminResource("admin/admin-theme.js") }
private val adminDashboardBrandingJs: String by lazy { loadAdminResource("admin/admin-branding.js") }
private val adminDashboardJs: String by lazy { loadAdminResource("admin/admin.js") }

private fun loadAdminResource(path: String): String =
    checkNotNull(AdminSessionAttemptLimiter::class.java.classLoader.getResourceAsStream(path)) {
        "Missing admin resource: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }


@kotlinx.serialization.Serializable
data class OpsSnapshotResponse(
    val users: Long,
    val messages: Long,
    val botsTotal: Long,
    val botsEnabled: Long,
    val botsWithWebhook: Long,
    val pollsTotal: Long,
    val pollsOpen: Long,
    val pollVotes: Long,
    val generatedAt: Long
)

/**
 * 8.48 修复 M1/M2：把时间列按「Unix 天编号」（timestamp / 86400000）分组的 Exposed 表达式，
 * 供趋势统计 SQL GROUP BY 聚合（管理仪表盘 /trends、/rich-trends）。
 * 8.63 修复：`$column` 字符串插值会输出 Kotlin 对象全限定路径（com.maodouchat.server.db.Users），
 * H2 把 `com` 误当数据库名报 "Database COM not found"（PostgreSQL 同样报错）。
 * 改用 column.toQueryBuilder 输出正确的「表名.列名」，且 CAST 用跨库 BIGINT（非 MySQL 的 SIGNED）。
 */
private fun dayBucketExpression(column: Column<Long>): Expression<Long> =
    object : Expression<Long>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("CAST(")
            column.toQueryBuilder(queryBuilder)
            queryBuilder.append(" / 86400000 AS BIGINT)")
        }
    }
