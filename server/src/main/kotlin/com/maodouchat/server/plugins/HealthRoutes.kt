package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.HealthStatusResponse
import com.maodouchat.server.service.CacheService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths

/** Dedicated limiter for /health/metrics so monitoring scraping never consumes the /api/ budget. */
private val healthMetricsLimiter = BoundedRateLimiter()

/**
 * 健康检查与状态路由（从 Routing.kt 拆分）。
 * 这些端点不需要 JWT，供部署探针和监控使用。
 */
fun Route.configureHealthRoutes() {
    // NOTE: do NOT register get("/") here — Routing.kt serves the public website at "/".
    // Liveness probes should use /health/live.
    get("/health/live") {
        call.respond(HealthStatusResponse(status = "ok"))
    }
    get("/health/ready") {
        call.respondReadiness()
    }
    // Compatibility alias for existing deployment checks. This endpoint intentionally needs no JWT.
    get("/api/health") {
        call.respondReadiness()
    }

    // Detailed server status for monitoring (no JWT required, but no sensitive info exposed)
    get("/api/status") {
        call.respondText(
            """{"status":"ok","service":"Maodouchat Server","env":"${ServerConfig.appEnv}","version":"1.0.0"}""",
            contentType = io.ktor.http.ContentType.Application.Json
        )
    }

    // Richer observability: JVM runtime, rate-limit counters, per-cache stats, gauges.
    // No JWT (ops/monitoring), but guarded by a dedicated per-IP limiter so the public
    // endpoint cannot be scraped into a DoS. Does NOT touch the /api/ global budget.
    get("/health/metrics") {
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
            put("onlineUsers", onlineUserIds().size)
        }
        val body = buildJsonObject {
            put("timestamp", System.currentTimeMillis())
            put("env", ServerConfig.appEnv)
            put("runtime", runtime)
            put("rateLimit", rateLimit)
            put("cache", cache)
            put("gauges", gauges)
        }
        // JsonObject.toString() emits valid JSON; avoids depending on a content-negotiation converter.
        call.respondText(body.toString(), contentType = ContentType.Application.Json)
    }

    // Build/version info for deployment dashboards. No JWT. Version is env-driven
    // (APP_VERSION); there is no canonical ServerConfig.appVersion today, so fall back to "dev".
    get("/health/info") {
        val version = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "dev"
        val info = buildJsonObject {
            put("service", "Maodouchat Server")
            put("version", version)
            put("env", ServerConfig.appEnv)
            put("timestamp", System.currentTimeMillis())
        }
        call.respondText(info.toString(), contentType = ContentType.Application.Json)
    }

    // 按需贴纸包清单（客户端 slim/OnDemandStickerStore，B1 包体瘦身）。
    // 运营商可在 STORAGE_DIR/stickers-manifest.json 放置贴纸包清单（格式：
    // {"version":1,"packs":[{"id":"...","stickers":[{"name":"...","url":"/static/...","sha256":"..."}]}]}，
    // url 可为相对路径）；文件存在则原样返回，不存在返回空清单——客户端静默回退内置表情，不影响聊天。
    get("/api/stickers/manifest.json") {
        val manifestFile = Paths.get(ServerConfig.storageDir).resolve("stickers-manifest.json").toFile()
        val body = if (manifestFile.isFile) {
            runCatching { manifestFile.readText().trim() }
                .getOrElse { STICKER_MANIFEST_EMPTY }
                .takeIf { it.isNotBlank() }
                ?: STICKER_MANIFEST_EMPTY
        } else {
            STICKER_MANIFEST_EMPTY
        }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(body, contentType = ContentType.Application.Json)
    }

    // 按需贴纸文件：放置于 STORAGE_DIR/stickers/<packId>/<file>。
    // packId/file 均做字符白名单 + canonical 路径前缀校验，杜绝路径穿越。
    get("/static/stickers/{packId}/{name}") {
        val rawPack = call.parameters["packId"].orEmpty()
        val rawName = call.parameters["name"].orEmpty()
        val packId = rawPack.replace(Regex("[^A-Za-z0-9_-]"), "").take(40)
        val name = rawName.replace(Regex("[^A-Za-z0-9._-]"), "").take(80)
        if (packId.isEmpty() || name.isEmpty() || packId != rawPack || name != rawName) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid sticker path"))
            return@get
        }
        val storageRoot = Paths.get(ServerConfig.storageDir).toAbsolutePath().normalize()
        val base = storageRoot.resolve("stickers").normalize()
        val file = base.resolve(packId).resolve(name).normalize().toFile()
        if (!file.isFile || !file.canonicalFile.toPath().startsWith(base)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("sticker not found"))
            return@get
        }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondFile(file)
    }
}

private const val STICKER_MANIFEST_EMPTY =
    """{"version":1,"packs":[]}"""

internal suspend fun ApplicationCall.respondReadiness() {
    val databaseReady = runCatching {
        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT 1") { result -> result.next() && result.getInt(1) == 1 } ?: false
        }
    }.getOrDefault(false)
    val storageReady = runCatching {
        val path = Paths.get(ServerConfig.storageDir).toAbsolutePath().normalize()
        Files.isDirectory(path) && Files.isWritable(path)
    }.getOrDefault(false)
    val checks = linkedMapOf(
        "database" to if (databaseReady) "ok" else "unavailable",
        "storage" to if (storageReady) "ok" else "unavailable"
    )
    if (databaseReady && storageReady) {
        respond(HealthStatusResponse(status = "ready", checks = checks))
    } else {
        respond(HttpStatusCode.ServiceUnavailable, HealthStatusResponse(status = "not_ready", checks = checks))
    }
}
