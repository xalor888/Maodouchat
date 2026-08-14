package com.maodouchat.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Date
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

// ═══ Developer-account session (dev_session JWT) ═══
// A short-lived JWT minted from email+password login. It reuses the same
// HMAC secret + issuer as JwtConfig so JwtConfig.verifier accepts it, and
// carries a token_use=dev_session claim that authenticateDevSession enforces.
// Like admin_session tokens, it also embeds the user's accessTokenVersion so
// password-change / logout-all / suspension automatically invalidate it.
private const val DEV_SESSION_VALIDITY_MS = 2L * 60 * 60 * 1000 // 2 小时
private const val TOKEN_USE_DEV_SESSION = "dev_session"
private const val JWT_ISSUER = "maodouchat"

// Stateless repo wrappers; safe to share across requests (all ops open their own transactions).
private val devUserRepo = UserRepository()
private val devAuthTokenRepo = AuthTokenRepository()

/** Mint a 2-hour dev_session JWT for [userId]. */
private fun mintDevSessionToken(userId: String, tokenVersion: Long): String {
    val algorithm = Algorithm.HMAC256(ServerConfig.jwtSecret)
    val expiresAt = System.currentTimeMillis() + DEV_SESSION_VALIDITY_MS
    return JWT.create()
        .withIssuer(JWT_ISSUER)
        .withSubject(userId)
        .withJWTId(UUID.randomUUID().toString())
        .withClaim("token_version", tokenVersion)
        .withClaim("token_use", TOKEN_USE_DEV_SESSION)
        .withIssuedAt(Date())
        .withExpiresAt(Date(expiresAt))
        .sign(algorithm)
}

/**
 * Validate the dev_session JWT from the Authorization header and return the
 * owning userId, or null (responding 401 is the caller's job). Verifies the
 * signature/issuer/expiry via JwtConfig, enforces the dev_session purpose,
 * and re-checks isAccessTokenAllowed so suspension / version rotation revoke it.
 */
private fun devSessionUserId(call: ApplicationCall): String? {
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull() ?: return null
    val decoded = JwtConfig.verifyToken(bearer) ?: return null
    if (decoded.getClaim("token_use").asString() != TOKEN_USE_DEV_SESSION) return null
    val userId = decoded.subject ?: return null
    if (!devAuthTokenRepo.isAccessTokenAllowed(userId, JwtConfig.tokenVersion(decoded), decoded.id)) return null
    if (userId !in ServerConfig.developerUserIds) return null
    return userId
}

/** Return the bot only if it exists and is owned by [userId]; else null. */
private fun devSessionOwnedBot(botId: String, userId: String): BotRepository.BotDto? {
    val bot = BotRepository.get(botId) ?: return null
    return if (bot.ownerUserId == userId) bot else null
}

private suspend fun ApplicationCall.rejectIfDeveloperMaintenance(): Boolean {
    if (!RuntimeConfigService.isMaintenanceMode()) return false
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse(RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE).ifBlank {
            "System under maintenance"
        })
    )
    return true
}

/**
 * Developer portal API - richer data for bot developers.
 *
 * Two auth surfaces:
 *  - /api/developer/         : bot-token auth (X-Bot-Token / Bearer), scoped to one bot.
 *  - /api/developer-account/ : developer-account auth via a short-lived dev_session JWT
 *                                (minted from email+password login). Lets a developer
 *                                manage ALL their bots without per-bot tokens.
 */
fun Application.configureDeveloperRouting() {
    val developerLoginRateLimiter = BoundedRateLimiter()
    /** 8.131：开发者登录按账号限流（防轮换源 IP 爆破，与主登录 loginEmailRateLimiter 同策略）。 */
    val developerLoginEmailRateLimiter = BoundedRateLimiter()
    val developerBotCreateRateLimiter = BoundedRateLimiter()
    val developerBotTokenRateLimiter = BoundedRateLimiter()

    routing {
        route("/api/developer") {
            // ─── Dashboard ────────────────────────
            get("/dashboard") {
                val bot = authenticateDeveloperBot(call) ?: return@get
                val dashboard = buildDashboard(bot.id, bot.ownerUserId)
                call.respond(dashboard)
            }

            // ─── Per-bot analytics ────────────────
            get("/bots/{id}/analytics") {
                val bot = authenticateDeveloperBot(call) ?: return@get
                val targetBotId = call.parameters["id"].orEmpty()
                if (targetBotId != bot.id) {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("无权访问该机器人数据")
                    )
                }
                val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 90)
                val analytics = buildBotAnalytics(targetBotId, days)
                call.respond(analytics)
            }

            // ─── Structured logs ──────────────────
            get("/bots/{id}/logs") {
                val bot = authenticateDeveloperBot(call) ?: return@get
                val targetBotId = call.parameters["id"].orEmpty()
                if (targetBotId != bot.id) {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("无权访问该机器人日志")
                    )
                }
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val commandFilter = call.request.queryParameters["command"]?.trim()?.takeIf { it.isNotBlank() }
                val sinceMs = call.request.queryParameters["since"]?.toLongOrNull()

                val logs = transaction {
                    val query = BotCommandLogs.selectAll()
                        .where { BotCommandLogs.botId eq targetBotId }
                    if (commandFilter != null) {
                        query.andWhere { BotCommandLogs.command eq commandFilter }
                    }
                    if (sinceMs != null && sinceMs > 0) {
                        query.andWhere { BotCommandLogs.createdAt greater sinceMs }
                    }
                    // total 必须是「过滤后的总行数」而非本页条数——此前填 logs.size，
                    // 客户端按 limit/offset 翻页时无法判断是否还有下一页
                    val countQuery = BotCommandLogs.selectAll()
                        .where { BotCommandLogs.botId eq targetBotId }
                    if (commandFilter != null) {
                        countQuery.andWhere { BotCommandLogs.command eq commandFilter }
                    }
                    if (sinceMs != null && sinceMs > 0) {
                        countQuery.andWhere { BotCommandLogs.createdAt greater sinceMs }
                    }
                    val total = countQuery.count().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val rows = query.orderBy(
                        BotCommandLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                        BotCommandLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
                    )
                        .limit(limit, offset)
                        .map { row ->
                            BotLogEntry(
                                id = row[BotCommandLogs.id],
                                command = row[BotCommandLogs.command],
                                chatId = row[BotCommandLogs.chatId],
                                userId = row[BotCommandLogs.userId],
                                createdAt = row[BotCommandLogs.createdAt]
                            )
                        }
                    BotLogsResponse(logs = rows, total = total)
                }
                call.respond(logs)
            }

            // ─── Test webhook ─────────────────────
            post("/bots/{id}/test-webhook") {
                val bot = authenticateDeveloperBot(call) ?: return@post
                if (call.rejectIfDeveloperMaintenance()) return@post
                val targetBotId = call.parameters["id"].orEmpty()
                if (targetBotId != bot.id) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("无权操作该机器人")
                    )
                }
                val webhookUrl = bot.webhookUrl
                if (webhookUrl.isNullOrBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("该机器人未设置 webhook URL")
                    )
                }
                val testPayload = buildTestPayload(bot.id, bot.username)
                // 使用与真实 webhook 一致的 HMAC-SHA256 签名（signing input = "{ts}.{body}"，密钥为 bot.tokenHash）
                val tokenHash = BotRepository.getTokenHash(bot.id)
                val ts = System.currentTimeMillis()
                val headers = mutableMapOf(
                    "User-Agent" to "Maodouchat-BotWebhook-Test/1.0",
                    "X-Maodouchat-Timestamp" to ts.toString()
                )
                if (tokenHash != null) {
                    val signature = hmacSha256Hex(tokenHash, "$ts.$testPayload")
                    headers["X-Maodouchat-Signature"] = "sha256=$signature"
                }
                val startTime = System.currentTimeMillis()
                val result = try {
                    val responseSnapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        postPinnedWebhookJson(
                            url = webhookUrl,
                            body = testPayload,
                            headers = headers,
                            connectTimeoutMs = 4_000,
                            readTimeoutMs = 6_000,
                            maxResponseBodyBytes = 500
                        )
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    WebhookTestResult(
                        success = responseSnapshot.statusCode in 200..299,
                        statusCode = responseSnapshot.statusCode,
                        responseBody = responseSnapshot.body,
                        latencyMs = elapsed,
                        error = null
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    WebhookTestResult(
                        success = false,
                        statusCode = 0,
                        responseBody = "",
                        latencyMs = elapsed,
                        error = e.message?.take(200)
                    )
                }
                call.respond(result)
            }

            // ─── Capability manifest ──────────────
            get("/capabilities") {
                // 8.131：manifest 与具体 bot 无关——dev_session 无需 bot id（此前一个 bot
                // 都没有的开发者取不到这份 bot 无关的能力清单）；bot token 仍可用
                if (!authenticateDeveloperIdentity(call)) return@get
                call.respond(buildCapabilityManifest())
            }

            // ─── Comprehensive health check ───────
            get("/health") {
                val bot = authenticateDeveloperBot(call) ?: return@get
                val health = buildDeveloperHealth(bot.id, bot.ownerUserId)
                call.respond(health)
            }
        }

        // ═══ Developer-account routes (dev_session JWT auth) ═══
        // These let a developer log in with email+password and manage ALL their
        // bots without needing each bot's token. Auth via Authorization: Bearer
        // <dev_session JWT>, validated by authenticateDevSession().
        route("/api/developer-account") {
            val userRepo = devUserRepo
            val authTokenRepo = devAuthTokenRepo

            // ─── Login (email + password) ────────
            post("/login") {
                if (!developerLoginRateLimiter.acquire(
                        call.remoteHost(),
                        maxPerMinute = ServerConfig.authRateLimitPerMinute
                    )
                ) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("登录过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedText().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val email = obj["email"]?.jsonPrimitive?.content.orEmpty()
                val password = obj["password"]?.jsonPrimitive?.content.orEmpty()
                val totpCode = obj["totpCode"]?.jsonPrimitive?.content
                if (email.isBlank() || password.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮箱或密码不能为空"))
                }
                // 8.131：与主登录一致补按账号限流——此前仅按 IP 限流，攻击者轮换源 IP
                // 即可对同一开发者账号无限爆破（主登录早有 loginEmailRateLimiter 堵这个洞）
                val emailKey = runCatching { email.normalizedEmail() }.getOrDefault(email)
                if (!developerLoginEmailRateLimiter.acquire(emailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号尝试过于频繁，请稍后再试"))
                }
                val loginResult = userRepo.loginWithFactors(email, password, totpCode)
                when {
                    !loginResult.passwordOk -> {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("邮箱或密码错误", code = "AUTH_INVALID"))
                    }
                    loginResult.totpEnabled && !loginResult.totpOk -> {
                        // 200 so the console can surface the TOTP step without treating it as a transport error.
                        call.respond(
                            DevLoginResponse(
                                requiresTotp = true,
                                token = "",
                                userId = "",
                                email = email,
                                name = "",
                                bots = emptyList()
                            )
                        )
                    }
                    loginResult.user != null -> {
                        val user = checkNotNull(loginResult.user)
                        // 失败闭合：未配置开发者白名单时拒绝所有人，避免任意已登录账号绕过权限获取开发者会话（权限提升）。
                        // 必须通过 DEVELOPER_USER_IDS 显式授权才允许创建/管理机器人。
                        if (user.id !in ServerConfig.developerUserIds) {
                            call.respond(HttpStatusCode.Forbidden, ErrorResponse("开发者功能未启用或需要开发者权限"))
                            return@post
                        }
                        val tokenVersion = authTokenRepo.getAccessTokenVersion(user.id)
                        val devToken = mintDevSessionToken(user.id, tokenVersion)
                        val bots = BotRepository.listByOwner(user.id)
                        call.respond(
                            DevLoginResponse(
                                requiresTotp = false,
                                token = devToken,
                                userId = user.id,
                                email = user.email,
                                name = user.name,
                                bots = bots
                            )
                        )
                    }
                    else -> {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("邮箱或密码错误", code = "AUTH_INVALID"))
                    }
                }
            }

            // ─── Current user + bot list ─────────
            get("/me") {
                val userId = devSessionUserId(call)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                val user = userRepo.getById(userId)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户不存在"))
                val bots = BotRepository.listByOwner(userId)
                call.respond(DevMeResponse(userId = user.id, email = user.email, name = user.name, bots = bots))
            }

            // ─── Create bot ──────────────────────
            post("/bots") {
                val userId = devSessionUserId(call)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                if (call.rejectIfDeveloperMaintenance()) return@post
                if (!RuntimeConfigService.isBotsAllowed()) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                }
                if (!developerBotCreateRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建机器人太频繁，请稍后再试"))
                }
                val body = call.receiveBoundedText().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val username = obj["username"]?.jsonPrimitive?.content.orEmpty()
                val description = obj["description"]?.jsonPrimitive?.content
                when (val result = BotRepository.create(userId, name, username, description)) {
                    is BotRepository.BotCreateResult.Success -> call.respond(result.bot)
                    BotRepository.BotCreateResult.UsernameTaken ->
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人用户名已被占用"))
                    BotRepository.BotCreateResult.MaxBotsReached ->
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人数量已达上限"))
                    BotRepository.BotCreateResult.InvalidInput,
                    BotRepository.BotCreateResult.OwnerInvalid ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("创建机器人失败（用户名非法）"))
                }
            }

            // ─── Rotate token ────────────────────
            post("/bots/{id}/token") {
                val userId = devSessionUserId(call)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                if (call.rejectIfDeveloperMaintenance()) return@post
                if (!developerBotTokenRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
                }
                val botId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val bot = BotRepository.regenerateToken(botId, userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                call.respond(bot)
            }

            // ─── Set webhook ─────────────────────
            put("/bots/{id}/webhook") {
                val userId = devSessionUserId(call)
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                if (call.rejectIfDeveloperMaintenance()) return@put
                val botId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val body = call.receiveBoundedText().orEmpty()
                val url = runCatching {
                    Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content
                }.getOrNull()
                // secret is accepted for forward-compat but not persisted (no repo column yet).
                val bot = BotRepository.setWebhook(botId, userId, url)
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
                call.respond(bot)
            }

            // ─── Delete bot ──────────────────────
            delete("/bots/{id}") {
                val userId = devSessionUserId(call)
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                if (call.rejectIfDeveloperMaintenance()) return@delete
                val botId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val ok = BotRepository.delete(botId, userId)
                if (!ok) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                call.respond(
                buildJsonObject {
put("ok", true)
                }
            )
            }

            // ─── Enable / disable ────────────────
            put("/bots/{id}/enabled") {
                val userId = devSessionUserId(call)
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                if (call.rejectIfDeveloperMaintenance()) return@put
                val botId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val body = call.receiveBoundedText().orEmpty()
                val enabled = runCatching {
                    val p = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
                    p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
                }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
                val bot = BotRepository.setEnabled(botId, userId, enabled)
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                call.respond(bot)
            }

            // ─── Set command menu ────────────────
            put("/bots/{id}/commands") {
                val userId = devSessionUserId(call)
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                if (call.rejectIfDeveloperMaintenance()) return@put
                val botId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                if (devSessionOwnedBot(botId, userId) == null) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该机器人"))
                }
                val body = call.receiveBoundedText().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val arr = obj["commands"] as? JsonArray
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("commands array required"))
                // 8.48 修复 M5：逐项严格校验——任一条目非法即 400，禁止静默丢弃后误清空命令菜单
                //（此前 mapNotNull 把空 command/description 丢弃，全非法时 defs 为空 → 200 清空全部命令）。
                // 仅显式传空数组 = 合法清空。
                val defs = buildList {
                    for (item in arr) {
                        val o = item as? JsonObject
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid command item"))
                        val command = o["command"]?.jsonPrimitive?.content.orEmpty()
                        val description = o["description"]?.jsonPrimitive?.content.orEmpty()
                        if (command.isBlank() || description.isBlank()) {
                            return@put call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("invalid command (command and description required)")
                            )
                        }
                        add(BotRepository.BotCommandDef(command = command, description = description))
                    }
                }
                val saved = BotRepository.setMyCommands(botId, defs)
                    ?: return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid commands (max 100, unique a-z0-9_, description required)")
                    )
                call.respond(
                buildJsonObject {
put("ok", true)
put("commands", Json.parseToJsonElement(Json.encodeToString(saved)))
put("count", saved.size)
                }
            )
            }

            // ─── Get command menu ────────────────
            get("/bots/{id}/commands") {
                val userId = devSessionUserId(call)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                val botId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                if (devSessionOwnedBot(botId, userId) == null) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该机器人"))
                }
                val commands = BotRepository.getMyCommands(botId)
                call.respond(
                buildJsonObject {
put("commands", Json.parseToJsonElement(Json.encodeToString(commands)))
put("count", commands.size)
                }
            )
            }
        }
    }
}

// ─── Bot token authentication helper ───────────────

private suspend fun authenticateBot(call: ApplicationCall): BotRepository.BotDto? {
    val headerToken = call.request.headers["X-Bot-Token"].orEmpty()
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    val token = headerToken.ifBlank { bearer }
    if (token.isBlank()) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing bot token"))
        return null
    }
    val bot = BotRepository.authenticate(token)
    if (bot == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid bot token"))
        return null
    }
    return bot
}

/**
 * Unified auth for /api/developer/ routes. Accepts EITHER:
 *  - a bot token (X-Bot-Token / Bearer), via [authenticateBot], OR
 *  - a dev_session JWT (Authorization: Bearer), in which case the target bot is
 *    resolved from the path {id} (or ?bot_id= query for id-less routes) and must
 *    be owned by the dev-session user.
 * Returns the bot the request is scoped to, or null (after responding 401/403).
 */
private suspend fun authenticateDeveloperBot(call: ApplicationCall): BotRepository.BotDto? {
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    if (bearer.isNotBlank()) {
        val decoded = JwtConfig.verifyToken(bearer)
        if (decoded != null && decoded.getClaim("token_use").asString() == TOKEN_USE_DEV_SESSION) {
            val userId = decoded.subject
            if (userId.isNullOrBlank() ||
                // 8.48 修复 L3：dev_session 分支与 devSessionUserId 一致校验开发者白名单——
                // 运营清空白名单后，已签发会话在其 2 小时有效期内仍可访问 dashboard/analytics/test-webhook
                (userId !in ServerConfig.developerUserIds) ||
                !devAuthTokenRepo.isAccessTokenAllowed(userId, JwtConfig.tokenVersion(decoded), decoded.id)
            ) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                return null
            }
            val botId = call.parameters["id"]?.takeIf { it.isNotBlank() }
                ?: call.request.queryParameters["bot_id"]?.takeIf { it.isNotBlank() }
            if (botId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing bot id"))
                return null
            }
            val bot = devSessionOwnedBot(botId, userId)
            if (bot == null) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该机器人"))
                return null
            }
            return bot
        }
    }
    // No dev_session JWT -> fall back to bot-token auth.
    return authenticateBot(call)
}

/** 8.131：仅校验开发者身份（dev_session 或 bot token），不解析具体 bot——供 capabilities 等 bot 无关端点。 */
private suspend fun authenticateDeveloperIdentity(call: ApplicationCall): Boolean {
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    if (bearer.isNotBlank()) {
        val decoded = JwtConfig.verifyToken(bearer)
        if (decoded != null && decoded.getClaim("token_use").asString() == TOKEN_USE_DEV_SESSION) {
            val userId = decoded.subject
            if (userId.isNullOrBlank() ||
                (userId !in ServerConfig.developerUserIds) ||
                !devAuthTokenRepo.isAccessTokenAllowed(userId, JwtConfig.tokenVersion(decoded), decoded.id)
            ) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
                return false
            }
            return true
        }
    }
    return authenticateBot(call) != null
}

// 聚合查询内存上限：开发者看板/分析对 BotCommandLogs 做进程内 group-by，
// 不限流会物化全表行导致 OOM。加行上限防止自残式 OOM（极繁忙 bot 退化为近似值）。
private const val MAX_AGG_ROWS = 50_000

// ─── Dashboard builder ─────────────────────────────

private fun buildDashboard(botId: String, ownerUserId: String): DeveloperDashboardResponse {
    return transaction {
        val bot = BotRepository.get(botId)
        val totalCommands = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .count()
        val activeWebhook = !bot?.webhookUrl.isNullOrBlank()

        // Command frequency (last 24h)
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val commands24h = BotCommandLogs.selectAll()
            .where {
                (BotCommandLogs.botId eq botId) and
                    (BotCommandLogs.createdAt greater dayAgo)
            }
            .count()

        // Unique users (last 24h) — 8.39：SQL 侧 COUNT(DISTINCT)，此前全表物化可 OOM
        val uniqueUsers24h = BotCommandLogs.select(BotCommandLogs.userId)
            .where {
                (BotCommandLogs.botId eq botId) and
                    (BotCommandLogs.createdAt greater dayAgo) and
                    (BotCommandLogs.userId.isNotNull())
            }
            .withDistinct()
            .count()

        // Top commands
        val topCommands = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .limit(MAX_AGG_ROWS)
            .map { it[BotCommandLogs.command] }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .map { CommandUsageEntry(command = it.key, count = it.value) }

        // Pending updates count for display
        val pendingCount = BotRepository.countPendingUpdates(botId)

        DeveloperDashboardResponse(
            botId = botId,
            botName = bot?.name ?: "",
            botUsername = bot?.username ?: "",
            totalCommands = totalCommands,
            commandsLast24h = commands24h,
            uniqueUsersLast24h = uniqueUsers24h.toLong(),
            pendingUpdates = pendingCount,
            webhookConfigured = activeWebhook,
            topCommands = topCommands,
            generatedAt = System.currentTimeMillis()
        )
    }
}

// ─── Bot analytics builder ─────────────────────────

private fun buildBotAnalytics(botId: String, days: Int): BotAnalyticsResponse {
    val now = System.currentTimeMillis()
    val dayMs = 86_400_000L
    val cutoff = now - days * dayMs

    return transaction {
        val totalCommands = BotCommandLogs.selectAll()
            .where { (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater cutoff) }
            .count()

        val dailyStats = buildList<DailyStat> {
            // 8.48 修复 M9：按天 GROUP BY 聚合（此前逐日 2 次 count → 30 天 = 60 次查询）
            val dayBucket = dayBucketExpression(BotCommandLogs.createdAt)
            val commandCounts = BotCommandLogs
                .slice(dayBucket, BotCommandLogs.id.count())
                .selectAll()
                .where { (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater cutoff) }
                .groupBy(dayBucket)
                .toList()
                .associate { it[dayBucket] to it[BotCommandLogs.id.count()].toLong() }
            val uniqueBucket = dayBucketExpression(BotCommandLogs.createdAt)
            val uniqueCountExpr = BotCommandLogs.userId.countDistinct()
            val uniqueCounts = BotCommandLogs
                .slice(uniqueBucket, uniqueCountExpr)
                .selectAll()
                .where {
                    (BotCommandLogs.botId eq botId) and
                        (BotCommandLogs.createdAt greater cutoff) and
                        (BotCommandLogs.userId.isNotNull())
                }
                .groupBy(uniqueBucket)
                .toList()
                .associate { it[uniqueBucket] to it[uniqueCountExpr].toLong() }
            for (i in 0 until days) {
                val dayStart = now - (days - 1 - i) * dayMs
                val dayEnd = dayStart + dayMs
                add(DailyStat(
                    day = dayStart,
                    commandCount = commandCounts[dayStart / dayMs] ?: 0,
                    uniqueUsers = uniqueCounts[dayStart / dayMs] ?: 0
                ))
            }
        }

        val commandBreakdown = BotCommandLogs.selectAll()
            .where { (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater cutoff) }
            .limit(MAX_AGG_ROWS)
            .map { it[BotCommandLogs.command] }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(20)
            .map { CommandUsageEntry(command = it.key, count = it.value) }

        BotAnalyticsResponse(
            botId = botId,
            periodDays = days,
            totalCommands = totalCommands,
            dailyStats = dailyStats,
            commandBreakdown = commandBreakdown,
            generatedAt = now
        )
    }
}

// ─── Developer health check ────────────────────────

private fun buildDeveloperHealth(botId: String, ownerUserId: String): DeveloperHealthResponse {
    return transaction {
        val bot = BotRepository.get(botId)
        val webhookHealthy = runCatching {
            // Quick check: bot exists, webhook URL set, bot enabled
            bot != null && bot.enabled && !bot.webhookUrl.isNullOrBlank()
        }.getOrDefault(false)

        val pendingUpdates = BotRepository.countPendingUpdates(botId)
        val commandCount = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .count()

        val botHealth = BotHealthStatus(
            botId = botId,
            enabled = bot?.enabled ?: false,
            webhookConfigured = !bot?.webhookUrl.isNullOrBlank(),
            webhookUrl = bot?.webhookUrl?.take(80),
            pendingUpdates = pendingUpdates,
            totalCommands = commandCount
        )

        val serverHealth = ServerHealthStatus(
            serverTime = System.currentTimeMillis(),
            maintenanceMode = RuntimeConfigService.isMaintenanceMode(),
            botsAllowed = RuntimeConfigService.isBotsAllowed(),
            mediaUploadEnabled = RuntimeConfigService.isMediaUploadEnabled(),
            aiEnabled = RuntimeConfigService.isAiEnabled()
        )

        DeveloperHealthResponse(
            status = if (webhookHealthy && !RuntimeConfigService.isMaintenanceMode()) "healthy" else "degraded",
            bot = botHealth,
            server = serverHealth,
            checkedAt = System.currentTimeMillis()
        )
    }
}

// ─── Capability manifest ───────────────────────────

private fun buildCapabilityManifest(): CapabilityManifestResponse {
    return CapabilityManifestResponse(
        version = "1.0",
        messaging = MessagingCapabilities(
            canSendMessage = true,
            canSendImages = RuntimeConfigService.isImageSendEnabled(),
            canSendVideos = RuntimeConfigService.isVideoSendEnabled(),
            canSendFiles = RuntimeConfigService.isFileShareEnabled(),
            canSendVoice = RuntimeConfigService.isVoiceMessagesEnabled(),
            canSendMarkdown = RuntimeConfigService.isMarkdownEnabled(),
            maxMessageLength = 4_096,
            supportsReply = true,
            supportsForward = RuntimeConfigService.isMessageForwardingEnabled(),
            supportsPin = RuntimeConfigService.isMessagePinEnabled(),
            supportsEdit = RuntimeConfigService.isMessageEditEnabled(),
            supportsRevoke = RuntimeConfigService.isMessageRevokeEnabled(),
            supportsReaction = RuntimeConfigService.isReactionsEnabled()
        ),
        groups = GroupCapabilities(
            canJoinGroups = true,
            canCreateGroups = false,
            maxGroupSize = RuntimeConfigService.getInt(RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200),
            canReadGroupHistory = true,
            canManageMembers = false,
            supportsGroupPlay = RuntimeConfigService.isGroupPlayEnabled(),
            supportsPolls = RuntimeConfigService.isPollsEnabled()
        ),
        ai = AiCapabilities(
            translateEnabled = RuntimeConfigService.isAiTranslateEnabled(),
            summarizeEnabled = RuntimeConfigService.isAiSummaryEnabled(),
            rewriteEnabled = RuntimeConfigService.isAiRewriteEnabled(),
            suggestRepliesEnabled = RuntimeConfigService.isAiSuggestRepliesEnabled(),
            transcribeEnabled = RuntimeConfigService.isAiTranscribeEnabled(),
            analyzeImageEnabled = RuntimeConfigService.isAiAnalyzeImageEnabled(),
            analyzeFileEnabled = RuntimeConfigService.isAiAnalyzeFileEnabled(),
            semanticSearchEnabled = RuntimeConfigService.isAiSemanticSearchEnabled(),
            groupAssistantEnabled = RuntimeConfigService.isAiGroupAssistantEnabled()
        ),
        integrations = IntegrationCapabilities(
            webhookSupported = true,
            webhookMaxRetries = 3,
            webhookTimeoutSeconds = 15,
            supportedUpdateTypes = listOf(
                "message", "callback_query", "inline_query",
                "command", "member_join", "member_leave"
            ),
            maxCommands = 100
        )
    )
}

// ─── Test payload builder ──────────────────────────

private fun buildTestPayload(botId: String, botUsername: String): String {
    return """{
        "update_id": ${System.currentTimeMillis()},
        "type": "test",
        "bot_id": "$botId",
        "bot_username": "$botUsername",
        "timestamp": ${System.currentTimeMillis()},
        "message": {
            "message_id": "test_${System.currentTimeMillis()}",
            "from": {"id": "system", "name": "Maodouchat Test"},
            "chat": {"id": "test_chat", "type": "private"},
            "text": "This is a test webhook delivery from Maodouchat.",
            "date": ${System.currentTimeMillis()}
        }
    }"""
}

/** HMAC-SHA256 hex digest, matching BotWebhookService signing. */
private fun hmacSha256Hex(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    return raw.joinToString("") { "%02x".format(it) }
}

// ─── Response data classes ─────────────────────────

@Serializable
data class DeveloperDashboardResponse(
    val botId: String,
    val botName: String,
    val botUsername: String,
    val totalCommands: Long,
    val commandsLast24h: Long,
    val uniqueUsersLast24h: Long,
    val pendingUpdates: Long,
    val webhookConfigured: Boolean,
    val topCommands: List<CommandUsageEntry>,
    val generatedAt: Long
)

@Serializable
data class CommandUsageEntry(
    val command: String,
    val count: Int
)

@Serializable
data class BotAnalyticsResponse(
    val botId: String,
    val periodDays: Int,
    val totalCommands: Long,
    val dailyStats: List<DailyStat>,
    val commandBreakdown: List<CommandUsageEntry>,
    val generatedAt: Long
)

@Serializable
data class DailyStat(
    val day: Long,
    val commandCount: Long,
    val uniqueUsers: Long
)

@Serializable
data class BotLogEntry(
    val id: String,
    val command: String,
    val chatId: String? = null,
    val userId: String? = null,
    val createdAt: Long
)

@Serializable
data class BotLogsResponse(
    val logs: List<BotLogEntry>,
    val total: Int
)

@Serializable
data class WebhookTestResult(
    val success: Boolean,
    val statusCode: Int,
    val responseBody: String,
    val latencyMs: Long,
    val error: String? = null
)

@Serializable
data class DeveloperHealthResponse(
    val status: String,
    val bot: BotHealthStatus,
    val server: ServerHealthStatus,
    val checkedAt: Long
)

@Serializable
data class BotHealthStatus(
    val botId: String,
    val enabled: Boolean,
    val webhookConfigured: Boolean,
    val webhookUrl: String? = null,
    val pendingUpdates: Long,
    val totalCommands: Long
)

@Serializable
data class ServerHealthStatus(
    val serverTime: Long,
    val maintenanceMode: Boolean,
    val botsAllowed: Boolean,
    val mediaUploadEnabled: Boolean,
    val aiEnabled: Boolean
)

@Serializable
data class CapabilityManifestResponse(
    val version: String,
    val messaging: MessagingCapabilities,
    val groups: GroupCapabilities,
    val ai: AiCapabilities,
    val integrations: IntegrationCapabilities
)

@Serializable
data class MessagingCapabilities(
    val canSendMessage: Boolean,
    val canSendImages: Boolean,
    val canSendVideos: Boolean,
    val canSendFiles: Boolean,
    val canSendVoice: Boolean,
    val canSendMarkdown: Boolean,
    val maxMessageLength: Int,
    val supportsReply: Boolean,
    val supportsForward: Boolean,
    val supportsPin: Boolean,
    val supportsEdit: Boolean,
    val supportsRevoke: Boolean,
    val supportsReaction: Boolean
)

@Serializable
data class GroupCapabilities(
    val canJoinGroups: Boolean,
    val canCreateGroups: Boolean,
    val maxGroupSize: Int,
    val canReadGroupHistory: Boolean,
    val canManageMembers: Boolean,
    val supportsGroupPlay: Boolean,
    val supportsPolls: Boolean
)

@Serializable
data class AiCapabilities(
    val translateEnabled: Boolean,
    val summarizeEnabled: Boolean,
    val rewriteEnabled: Boolean,
    val suggestRepliesEnabled: Boolean,
    val transcribeEnabled: Boolean,
    val analyzeImageEnabled: Boolean,
    val analyzeFileEnabled: Boolean,
    val semanticSearchEnabled: Boolean,
    val groupAssistantEnabled: Boolean
)

@Serializable
data class IntegrationCapabilities(
    val webhookSupported: Boolean,
    val webhookMaxRetries: Int,
    val webhookTimeoutSeconds: Int,
    val supportedUpdateTypes: List<String>,
    val maxCommands: Int
)

// ═══ Developer-account response types ═══

@Serializable
data class DevLoginResponse(
    val requiresTotp: Boolean = false,
    val token: String,
    val userId: String,
    val email: String,
    val name: String = "",
    val bots: List<BotRepository.BotDto>
)

@Serializable
data class DevMeResponse(
    val userId: String,
    val email: String,
    val name: String,
    val bots: List<BotRepository.BotDto>
)

/** 8.48 修复 M9：按「Unix 天编号」分组的 Exposed 表达式（SQL GROUP BY 聚合 bot 日志趋势）。 */
private fun dayBucketExpression(column: Column<Long>): Expression<Long> =
    object : Expression<Long>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            // 8.63 修复：`$column` 插值会输出 Kotlin 全限定路径（H2 把 com 当库名报
            // "Database COM not found"），且 CAST 用跨库 BIGINT（非 MySQL 的 SIGNED）
            queryBuilder.append("CAST(")
            column.toQueryBuilder(queryBuilder)
            queryBuilder.append(" / 86400000 AS BIGINT)")
        }
    }
