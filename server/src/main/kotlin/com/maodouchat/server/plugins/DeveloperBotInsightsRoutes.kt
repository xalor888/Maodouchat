package com.maodouchat.server.plugins

import com.maodouchat.server.common.postPinnedWebhookJson
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WebhookTestResult
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun Route.configureDeveloperBotInsightsRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {
    val developerWebhookTestRateLimiter = BoundedRateLimiter()

    // ─── Dashboard ────────────────────────
    get("/dashboard") {
        val bot = authenticateDeveloperBot(call) ?: return@get
        val dashboard = developerAnalytics.dashboard(bot.id)
        call.respond(dashboard)
    }

    // ─── Per-bot analytics ────────────────
    get("/bots/{id}/analytics") {
        val bot = authenticateDeveloperBot(call) ?: return@get
        val targetBotId = parseRawOrEmpty(call.parameters, "id")
        if (targetBotId != bot.id) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("无权访问该机器人数据")
            )
        }
        val days = parseDeveloperAnalyticsDays(call.request.queryParameters)
        val analytics = developerAnalytics.analytics(targetBotId, days)
        call.respond(analytics)
    }

    // ─── Structured logs ──────────────────
    get("/bots/{id}/logs") {
        val bot = authenticateDeveloperBot(call) ?: return@get
        val targetBotId = parseRawOrEmpty(call.parameters, "id")
        if (targetBotId != bot.id) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("无权访问该机器人日志")
            )
        }
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val commandFilter = parseDeveloperCommandFilter(call.request.queryParameters)
        val sinceMs = parseOptionalLong(call.request.queryParameters, "since")

        val logs = developerAnalytics.commandLogs(
            botId = targetBotId,
            commandFilter = commandFilter,
            sinceMs = sinceMs,
            limit = limit,
            offset = offset,
        )
        call.respond(logs)
    }

    // ─── Test webhook ─────────────────────
    post("/bots/{id}/test-webhook") {
        val bot = authenticateDeveloperBot(call) ?: return@post
        if (call.rejectIfDeveloperMaintenance()) return@post
        if (!developerWebhookTestRateLimiter.acquire(bot.id, maxPerMinute = 10)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val targetBotId = parseRawOrEmpty(call.parameters, "id")
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
// Mac 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
private val hmacSha256MacThreadLocal = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

private fun hmacSha256Hex(secret: String, message: String): String {
    val mac = hmacSha256MacThreadLocal.get().apply { reset() }
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    return raw.joinToString("") { "%02x".format(it) }
}
