package com.maodouchat.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID

// ═══ Developer-account session (dev_session JWT) ═══
// A short-lived JWT minted from email+password login, carrying a
// token_use=dev_session claim that every dev-session entry point enforces.
// Like admin_session tokens, it also embeds the user's accessTokenVersion so
// password-change / logout-all / suspension automatically invalidate it.
//
// G328c：签名密钥从 JWT_SECRET 改为**用途子密钥**（默认由主密钥派生，可用
// DEVELOPER_SESSION_SECRET 独立轮换）。此前它与 access token 共用一把密钥 + 同一个
// issuer，于是两类令牌在 `JwtConfig.verifier` 眼里是同一族，隔离只靠一个 `token_use`
// 字符串；现在 dev_session 有独立 verifier，拿 access token 或主密钥以外的任何
// 输入都构造不出可被接受的后台会话。

private const val DEV_SESSION_VALIDITY_MS = 2L * 60 * 60 * 1000 // 2 小时

private const val TOKEN_USE_DEV_SESSION = "dev_session"

private const val JWT_ISSUER = "maodouchat"

// Stateless repo wrappers; safe to share across requests (all ops open their own transactions).

internal val devUserRepo = UserRepository()

internal val devAuthTokenRepo = AuthTokenRepository()

internal val devParticipantRepo = ConversationParticipantRepository()

internal val devJson = Json { ignoreUnknownKeys = true }

/**
 * dev_session 专用验证器。按密钥值缓存——[ServerConfig.developerSessionSecret] 是
 * `get()`（每次读环境/系统属性），测试会在同一 JVM 内切换 JWT_SECRET，缓存必须跟着变。
 */

private var cachedDevSessionVerifier: Pair<String, JWTVerifier>? = null

internal fun devSessionVerifier(): JWTVerifier {
    val secret = ServerConfig.developerSessionSecret
    cachedDevSessionVerifier?.let { if (it.first == secret) return it.second }
    val verifier = JWT.require(Algorithm.HMAC256(secret)).withIssuer(JWT_ISSUER).build()
    cachedDevSessionVerifier = secret to verifier
    return verifier
}

/** Mint a 2-hour dev_session JWT for [userId]. */

internal fun mintDevSessionToken(userId: String, tokenVersion: Long): String {
    val algorithm = Algorithm.HMAC256(ServerConfig.developerSessionSecret)
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
 * signature/issuer/expiry with the **dev_session 专用密钥**, enforces the
 * dev_session purpose, and re-checks isAccessTokenAllowed so suspension /
 * version rotation revoke it.
 */

internal fun devSessionUserId(call: ApplicationCall): String? {
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull() ?: return null
    val decoded = runCatching { devSessionVerifier().verify(bearer) }.getOrNull() ?: return null
    if (decoded.getClaim("token_use").asString() != TOKEN_USE_DEV_SESSION) return null
    val userId = decoded.subject ?: return null
    if (!devAuthTokenRepo.isAccessTokenAllowed(userId, JwtConfig.tokenVersion(decoded), decoded.id)) return null
    if (userId !in ServerConfig.developerUserIds) return null
    return userId
}

/** Return the bot only if it exists and is owned by [userId]; else null. */

internal fun devSessionOwnedBot(botId: String, userId: String): BotRepository.BotDto? {
    val bot = BotRepository.get(botId) ?: return null
    return if (bot.ownerUserId == userId) bot else null
}

internal suspend fun ApplicationCall.rejectIfDeveloperMaintenance(): Boolean {
    if (!RuntimeConfigService.isMaintenanceMode()) return false
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse(RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE).ifBlank {
            "System under maintenance"
        })
    )
    return true
}

internal suspend fun ApplicationCall.respondDeveloperBotUnavailable() {
    respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable or disabled", code = "BOT_UNAVAILABLE"))
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
    val developerAnalytics = DeveloperAnalyticsRepository()

    routing {
        route("/api/developer") {
            configureDeveloperBotInsightsRoutes(developerAnalytics)
            configureDeveloperPortalRoutes(developerAnalytics)
        }

        // ═══ Developer-account routes (dev_session JWT auth) ═══
        // These let a developer log in with email+password and manage ALL their
        // bots without needing each bot's token. Auth via Authorization: Bearer
        // <dev_session JWT>, validated by authenticateDevSession().
        route("/api/developer-account") {
            configureDeveloperAccountRoutes()
        }
    }
}

// ─── Bot token authentication helper ───────────────

private suspend fun authenticateBotToken(call: ApplicationCall): BotRepository.BotDto? {
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
 *  - a bot token (X-Bot-Token / Bearer), via [authenticateBotToken], OR
 *  - a dev_session JWT (Authorization: Bearer), in which case the target bot is
 *    resolved from the path {id} (or ?bot_id= query for id-less routes) and must
 *    be owned by the dev-session user.
 * Returns the bot the request is scoped to, or null (after responding 401/403).
 */

internal suspend fun authenticateDeveloperBot(call: ApplicationCall): BotRepository.BotDto? {
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    if (bearer.isNotBlank()) {
        val decoded = runCatching { devSessionVerifier().verify(bearer) }.getOrNull()
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
            val botId = parseNonBlankOrNull(call.parameters, "id")
                ?: parseNonBlankOrNull(call.request.queryParameters, "bot_id")
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
    return authenticateBotToken(call)
}

/** 8.131：仅校验开发者身份（dev_session 或 bot token），不解析具体 bot——供 capabilities 等 bot 无关端点。 */

internal suspend fun authenticateDeveloperIdentity(call: ApplicationCall): Boolean {
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    if (bearer.isNotBlank()) {
        val decoded = runCatching { devSessionVerifier().verify(bearer) }.getOrNull()
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
    return authenticateBotToken(call) != null
}

// ─── Dashboard builder ─────────────────────────────


// ─── Bot analytics builder ─────────────────────────


// ─── Developer health check ────────────────────────

// ─── Response data classes ─────────────────────────

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
    val clientAiEnabled: Boolean,
    val contentModerationEnabled: Boolean
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


/** UTC 日起点（与 AdminRouting trends 的 dayStartNorm 一致），供 analytics 点位对齐 SQL CAST(ts/86400000)。 */

internal fun unixDayStartMs(epochMs: Long, dayMs: Long = 86_400_000L): Long =
    epochMs - (epochMs % dayMs)

// dayBucketExpression 已统一到 AdminSupport.kt（内部共享版本）。
