package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Configures rate limiting for all /api/ routes and the /ws handshake.
 *
 * Policy (9.3xx rework — production 429 storms):
 * - Authenticated requests are budgeted PER USER (JWT subject), with a generous limit, so a
 *   legitimate client burst (chat list + per-chat sync + prekey bundles for every group member)
 *   can never trip the limiter, and users sharing one NAT IP do not starve each other.
 * - Unauthenticated requests (login/register/public endpoints, invalid JWT) keep the
 *   per-IP budget — brute force / unauthenticated DoS protection.
 * - `/ws` is authenticated-only; a valid Bearer is billed per user so NAT reconnects
 *   do not share the unauthenticated IP budget. Invalid/missing JWT stays on the IP budget.
 * - Attachment chunk uploads have their own per-user limits and are skipped here.
 *
 * JWT is resolved from the Authorization header here: [ApplicationCallPipeline.Plugins]
 * runs before `authenticate { }` route pipelines, so [JWTPrincipal] is almost always
 * null at this intercept (9.3xx dual-budget never engaged; NAT users were 429'd).
 */
fun Application.configureRateLimit() {
    if (attributes.contains(RateLimitInstalledKey)) return
    attributes.put(RateLimitInstalledKey, Unit)
    val (ipLimiter, ipLifecycle) = RateLimitPools.ipPool()
    val (userLimiter, userLifecycle) = RateLimitPools.userPool()
    val logger = LoggerFactory.getLogger("RateLimitConfig")
    environment.monitor.subscribe(ApplicationStopped) {
        ipLimiter.shutdown(ipLifecycle)
        userLimiter.shutdown(userLifecycle)
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        // Only rate-limit API endpoints + the /ws handshake（握手在认证前要做 JWT 校验和两次
        // DB 查询，不设 IP 限流会成为未认证客户端的免费打点面）
        if (!path.startsWith("/api/") && path != "/ws") return@intercept
        // Skip attachment upload bodies only (per-user limits on session/chunk/one-shot).
        // Downloads GET /api/attachments/{id} stay under the per-user limit (bandwidth DoS).
        val method = call.request.httpMethod.value
        val isAttachmentUploadBody =
            (method == "POST" || method == "PUT" || method == "PATCH") &&
                (path.startsWith("/api/attachment-uploads") ||
                    path == "/api/attachments" ||
                    path.matches(attachmentChunkPathRegex))
        if (isAttachmentUploadBody) return@intercept
        // Skip health checks
        if (path == "/api/health" || path == "/api/status") return@intercept
        // WebRTC .so is a one-shot ~10MB public download. Billing it on the
        // unauthenticated IP bucket (emulator/NAT) 429s first-call forever.
        if (path.startsWith("/api/webrtc/lib")) return@intercept
        if (path == "/api/internal/app-update" || path == "/api/public/app-update/latest.apk") return@intercept
        // WS handshake with a valid JWT is billed on the per-user bucket below.
        // Brief reconnect flaps (heartbeat dead-after 30s) must not 429 the NAT IP
        // budget and toast 「频繁请稍后再试」 on every chat list refresh.

        val clientIp = call.remoteHost()
        if (RuntimeConfigService.isIpBlocked(clientIp)) {
            logger.warn("Blocked IP denied: {} on path: {}", clientIp, path)
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to "access denied"
            ))
            finish()
            return@intercept
        }
        val userId = authenticatedUserId(call)
        val (decision, budgetKey) = if (userId != null) {
            userLimiter.tryAcquire("user:$userId") to "user:$userId"
        } else {
            ipLimiter.tryAcquire(clientIp) to "ip:$clientIp"
        }
        if (!decision.allowed) {
            logThrottled(
                logger,
                "$budgetKey|$path",
                "Rate limit exceeded for {} on path: {} (retryAfter={}s, remaining={})",
                budgetKey, path, decision.retryAfterSeconds, decision.remaining
            )
            decision.retryAfterSeconds?.let { secs ->
                call.response.headers.append(HttpHeaders.RetryAfter, secs.coerceAtLeast(1).toString())
            }
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(
                    "请求过于频繁，请稍后重试",
                    code = "rate_limited",
                    retryAfterSeconds = decision.retryAfterSeconds?.coerceAtLeast(1)
                )
            )
            finish()
        }
    }
}

/**
 * Prefer an already-populated [JWTPrincipal] (if a future pipeline order change fills it),
 * otherwise verify the Bearer token the same way sockets / routes do. Invalid JWTs
 * return null and stay on the unauthenticated IP budget.
 */
private fun authenticatedUserId(call: ApplicationCall): String? {
    call.authentication.principal<JWTPrincipal>()
        ?.payload?.subject?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return call.request.headers[HttpHeaders.Authorization]
        .bearerTokenOrNull()
        ?.let { JwtConfig.getUserIdFromToken(it) }
        ?.takeIf { it.isNotBlank() }
}

/** Rate-limit warning logs are throttled per (bucket,path) so a storm does not flood the log. */
private val rateLimitLogTimes = ConcurrentHashMap<String, Long>()

private val rateLimitLogGate = Any()

private fun logThrottled(logger: org.slf4j.Logger, key: String, format: String, vararg args: Any?) {
    val now = System.currentTimeMillis()
    val last = rateLimitLogTimes[key]
    if (last == null || now - last >= 5_000L) {
        synchronized(rateLimitLogGate) {
            val last2 = rateLimitLogTimes[key]
            if (last2 == null || now - last2 >= 5_000L) {
                rateLimitLogTimes[key] = now
                logger.warn(format, *args)
            }
        }
    }
}

private val RateLimitInstalledKey = AttributeKey<Unit>("MaodouchatRateLimitInstalled")
