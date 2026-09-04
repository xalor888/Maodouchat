package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.DispositionService
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.UserDispositionService
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
import io.ktor.server.response.respondBytes
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
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
internal fun Application.configureAdminManagementRouting(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    reportRepo: ReportWorkflow = ReportWorkflow()
) {
    val authTokenRepo = AuthTokenRepository()
    val groupMediaReferenceRepo = GroupMediaReferenceRepository()
    val groupInvitationService = GroupInvitationService(GroupInvitationRepository())
    // 管理后台 SPA 静态资产与页面服务（HTML/CSS/JS/logo，见 AdminAssets.kt）
    configureAdminAssets()
    routing {
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
                    // 9.247：自部署高频踩坑——MASTER_ADMINS 填了邮箱而非 userId，
                    // 报错附带配置指引便于自查（不泄露当前配置值）
                    // 9.4xx：同时记录尝试账号（名+id），运维可据此排查「登的是哪个号」
                    val who = userRepo.getById(userId)
                    adminAuditLogger.warn(
                        "Admin session denied for user {} ({} / {}); not in MASTER_ADMINS",
                        userId, who?.name.orEmpty(), who?.email.orEmpty()
                    )
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(
                            "需要主管理员权限：当前登录账号为 ${who?.name.orEmpty().ifBlank { "?" }}（${userId}），" +
                                "请确认服务端 MASTER_ADMINS 环境变量包含该 userId（非邮箱）；" +
                                "若登录的是其他账号，请改用主管理员账号重新登录"
                        )
                    )
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

            configureAdminObservabilityRoutes(ServerConfig)


            // ─── 用户治理（见 AdminUsersRouting.kt） ───
            configureAdminUsersRoutes(
                userRepo = userRepo,
                postRepo = postRepo,
                authTokenRepo = authTokenRepo,
                groupMediaReferenceRepo = groupMediaReferenceRepo,
                userDispositionService = UserDispositionService(userRepo),
            )

            // ─── 内容管理（动态 / 评论，见 AdminContentRouting.kt） ───
            configureAdminContentRoutes(postRepo)

            // ─── 群聊管理（见 AdminChatsRouting.kt） ───
            configureAdminChatsRoutes()

            // ─── 举报 + 风控（见 AdminModerationRouting.kt） ────
            configureAdminModerationRoutes(
                reportRepo = reportRepo,
                postRepo = postRepo,
                userRepo = userRepo,
                moderationRuleRepo = moderationRuleRepo,
                authTokenRepo = authTokenRepo,
            )

            // ─── 诊断（AI 审计 / 推送令牌 / Bot / Ops 快照，见 AdminDiagnosticsRouting.kt） ───
            configureAdminDiagnosticsRoutes()

            // ─── 系统安全快照 + 运营配置（见 AdminSystemRouting.kt） ───
            configureAdminSystemRoutes()
            configureAdminExportsRoutes(authTokenRepo)
            configureAdminBulkRoutes(authTokenRepo, groupInvitationService)

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
                // Metadata-only search. Human payloads remain opaque to the server.
                val rows = transaction {
                    var query = MessagingV2Messages.selectAll()
                    if (chatId.isNotBlank()) query = query.andWhere { MessagingV2Messages.conversationId eq chatId }
                    if (userId.isNotBlank()) query = query.andWhere { MessagingV2Messages.senderUserId eq userId }
                    query = query.andWhere {
                        (MessagingV2Messages.recordClass eq com.maodouchat.server.messaging.v2.MessagingV2RecordClass.MESSAGE) and
                        (MessagingV2Messages.conversationId notInSubQuery (
                            Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
                        ))
                    }
                    if (q.isNotBlank()) {
                        val like = "%" + escapeLikePattern(q.take(80)) + "%"
                        query = query.andWhere {
                            (MessagingV2Messages.id like like) or
                                (MessagingV2Messages.conversationId like like) or
                                (MessagingV2Messages.senderUserId like like) or
                                (MessagingV2Messages.kind like like)
                        }
                    }
                    query.orderBy(
                        MessagingV2Messages.serverTimestamp to SortOrder.DESC,
                        MessagingV2Messages.id to SortOrder.DESC,
                    )
                        .limit(limit, offset.toLong())
                        .map {
                            buildJsonObject {
                                put("id", it[MessagingV2Messages.id])
                                put("chatId", it[MessagingV2Messages.conversationId])
                                put("senderId", it[MessagingV2Messages.senderUserId])
                                put("type", it[MessagingV2Messages.kind])
                                put("timestamp", it[MessagingV2Messages.serverTimestamp])
                                put("status", "DURABLE")
                                put("sealedSender", true)
                                put("contentPreview", "")
                                put("e2eeLikely", it[MessagingV2Messages.kind] != "SERVICE")
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
                    com.maodouchat.server.plugins.ConnectionRegistry.onlineUserIds()
                } catch (_: Exception) {
                    emptyList()
                }
                var delivered = 0
                for (uid in onlineIds) {
                    try {
                        com.maodouchat.server.plugins.LocalRealtimeBus.publish(uid, envelope)
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
                    Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
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
                    val changed = Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
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
// 管理后台共享支撑（DTO/鉴权/审计/限流器/CSV/SQL 表达式）已迁至 AdminSupport.kt。
