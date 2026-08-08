package com.maodouchat.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.security.MessageDigest

/**
 * Ktor plugin that adds Cache-Control headers, ETag generation,
 * and conditional request (304 Not Modified) handling.
 *
 * Configurable TTL per route prefix with auto-matching.
 */
val CachingPlugin = createApplicationPlugin(name = "MaodouchatCaching") {
    val routeTtls = mutableMapOf<String, Long>()

    // Default TTLs per route prefix
    routeTtls["/api/public/status"] = 30_000L          // 30s
    routeTtls["/api/developer/capabilities"] = 300_000L // 5min
    routeTtls["/api/developer/health"] = 60_000L        // 1min
    routeTtls["/assets/"] = 86_400_000L                 // 24h for static assets
    routeTtls["/admin/assets/"] = 3_600_000L            // 1h for admin assets

    // 8.47 安全修复：ETag/304 短路只允许发生在「无需鉴权的公共/静态路径」。
    // `/api/developer/*` 配置了缓存 TTL，但 GET 在 authenticateDeveloperBot 执行前就被
    // 插件 304 短路——任何无 token 客户端携带匹配 If-None-Match 即可探测受保护端点
    //（绕过鉴权、泄露端点存在性/活跃性）。Cache-Control 头仍照发，304 判定收窄到白名单。
    val publicConditionalPaths = listOf("/api/public/status", "/assets/", "/admin/assets/")

    onCall { call ->
        val path = call.request.path()
        val ttl = findMatchingTtl(path, routeTtls)

        // Set Cache-Control header for matched routes
        if (ttl != null && ttl > 0) {
            val maxAge = (ttl / 1000).toInt()
            val directive = if (ttl <= 60_000L) {
                "public, max-age=$maxAge"
            } else {
                "public, max-age=$maxAge, must-revalidate"
            }
            call.response.header(HttpHeaders.CacheControl, directive)
        }

        // ETag handling for GET requests with cache (public paths only — never short-circuit auth)
        val isPublicConditional = publicConditionalPaths.any { path.startsWith(it) || path == it.removeSuffix("/") }
        if (call.request.local.method.value == "GET" && ttl != null && ttl > 0 && isPublicConditional) {
            val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
            // Avoid division by zero when ttl == 1; fall back to raw millisecond bucket.
            val bucket = if (ttl >= 2) System.currentTimeMillis() / (ttl / 2) else System.currentTimeMillis()
            val etagValue = generateWeakEtag(path, bucket)

            call.response.header(HttpHeaders.ETag, etagValue)

            // 304 Not Modified — client's cached version is still valid
            if (ifNoneMatch != null && ifNoneMatch == etagValue) {
                call.respond(HttpStatusCode.NotModified)
            }
        }
    }
}

/**
 * Find the TTL for a given path by matching route prefixes.
 * 8.47 修复：段级匹配——`/api/public/statusXYZ` 不再命中 `/api/public/status` 的 TTL
 *（此前 startsWith 无路径边界，仅为多余缓存头，现已收窄）。
 * 返回 null 如果未配置 TTL。
 */
private fun findMatchingTtl(path: String, routeTtls: Map<String, Long>): Long? {
    // Match longest prefix first; key 以 '/' 结尾 = 目录段前缀，否则精确段前缀
    return routeTtls.entries
        .filter { (key, _) ->
            if (key.endsWith("/")) path.startsWith(key)
            else path == key || path.startsWith("$key/")
        }
        .maxByOrNull { it.key.length }
        ?.value
}

/**
 * Generate a weak ETag from path and time bucket.
 * Uses time bucketing so ETag changes at TTL intervals rather than every request.
 */
private fun generateWeakEtag(path: String, timeBucket: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(path.toByteArray(Charsets.UTF_8))
    digest.update(timeBucket.toString().toByteArray(Charsets.UTF_8))
    val hash = digest.digest().take(16).joinToString("") { "%02x".format(it) }
    return "W/\"$hash\""
}

/**
 * Helper to configure route-level caching in a Routing block.
 * Call inside `routing { configureRouteCaching { ... } }`.
 */
fun Route.cacheStaticAssets() {
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val ext = path.substringAfterLast('.', "")

        val cacheControl = when {
            ext in setOf("css", "js", "woff2", "woff", "ttf", "otf") ->
                "public, max-age=2592000, immutable" // 30 days
            ext in setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "ico") ->
                "public, max-age=2592000, immutable"
            ext in setOf("html", "htm") ->
                "no-store, no-cache, must-revalidate"
            path.startsWith("/assets/") ->
                "public, max-age=2592000, immutable"
            else -> null
        }

        cacheControl?.let { call.response.header(HttpHeaders.CacheControl, it) }
    }
}

/**
 * For API responses: short cache with revalidation.
 */
fun Route.cacheApiResponse(maxAgeSeconds: Int = 30) {
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        call.response.header(
            HttpHeaders.CacheControl,
            "public, max-age=$maxAgeSeconds, must-revalidate"
        )
    }
}

/**
 * Compute ETag (SHA-256 truncated to 16 hex chars).
 */
fun computeETag(content: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(content)
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

fun computeETag(content: String): String = computeETag(content.toByteArray(Charsets.UTF_8))

/**
 * Check If-None-Match header against ETag.
 * Returns true if the client's cached version is valid (caller should return 304).
 */
fun checkNotModified(call: ApplicationCall, etag: String): Boolean {
    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
    return ifNoneMatch?.trim('*') == etag
}
