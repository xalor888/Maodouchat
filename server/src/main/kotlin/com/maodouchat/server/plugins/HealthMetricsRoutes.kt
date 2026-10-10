package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.CacheService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// 监控抓取用独立限流器，不占用 /api/ 全局限流预算。
private val healthMetricsLimiter = BoundedRateLimiter()

/** 运维指标：默认关闭（需 METRICS_TOKEN）；令牌常量时间比较，防时序侧信道。 */
internal fun Route.configureHealthMetricsRoutes() {
    get("/health/metrics") {
        val expected = ServerConfig.metricsToken
        if (expected.isBlank()) {
            // fail-closed：未配置即视为不开放。
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("metrics disabled; set METRICS_TOKEN on the server to enable", code = "METRICS_DISABLED"),
            )
            return@get
        }
        val presented = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()
        val authorized = presented.isNotEmpty() &&
            java.security.MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                presented.toByteArray(Charsets.UTF_8),
            )
        if (!authorized) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid metrics token", code = "METRICS_UNAUTHORIZED"))
            return@get
        }
        val ip = call.remoteHost()
        if (!healthMetricsLimiter.acquire(ip, maxPerMinute = 30)) {
            call.response.headers.append(HttpHeaders.RetryAfter, "2")
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many requests", code = "rate_limited"))
            return@get
        }
        val rt = Runtime.getRuntime()
        val runtime = buildJsonObject {
            put("freeHeapBytes", rt.freeMemory())
            put("totalHeapBytes", rt.totalMemory())
            put("usedHeapBytes", rt.totalMemory() - rt.freeMemory())
            put("maxHeapBytes", rt.maxMemory())
            put("processors", rt.availableProcessors())
            put("uptimeMillis", ManagementFactory.getRuntimeMXBean().uptime)
        }
        val rateLimit = GlobalRateLimiter.getInstance().stats().let { s ->
            buildJsonObject {
                put("allowed", s.allowed)
                put("rejected", s.rejected)
                put("totalBuckets", s.totalBuckets)
                put("maxBuckets", s.maxBuckets)
                put("maxPerMinute", s.maxPerMinute)
            }
        }
        val cache = CacheService.getInstance().cacheStats().let { stats ->
            buildJsonObject {
                stats.forEach { (name, s) ->
                    putJsonObject(name) {
                        put("size", s.size)
                        put("maxSize", s.maxSize)
                        put("ttlMs", s.ttlMs)
                        put("hits", s.hits)
                        put("misses", s.misses)
                        put("evictions", s.evictions)
                    }
                }
            }
        }
        val gauges = buildJsonObject {
            put("ConnectionRegistry.onlineUsers", ConnectionRegistry.onlineUserIds().size)
        }
        val body = buildJsonObject {
            put("timestamp", System.currentTimeMillis())
            put("runtime", runtime)
            put("rateLimit", rateLimit)
            put("cache", cache)
            put("gauges", gauges)
        }
        // JsonObject.toString() emits valid JSON; avoids depending on a content-negotiation converter.
        call.respondText(body.toString(), contentType = ContentType.Application.Json)
    }
}
