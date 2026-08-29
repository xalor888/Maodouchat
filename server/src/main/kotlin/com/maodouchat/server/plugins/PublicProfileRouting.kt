package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CacheService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Anonymous profile JSON and HTML endpoints. */
internal fun Route.configurePublicProfileRoutes(
    userRepository: UserRepository,
    cacheService: CacheService,
    rateLimiter: BoundedRateLimiter,
) {
    get("/api/public/profile/{username}") {
        if (!rateLimiter.acquire(call.remoteHost(), maxPerMinute = 60)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("请求过于频繁，请稍后再试"))
            return@get
        }
        val username = call.parameters["username"]?.trim()?.lowercase()?.removePrefix("@").orEmpty()
        if (username.isBlank() || username.length < 3) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("用户名无效"))
            return@get
        }
        val cacheKey = "user_profile:$username"
        val user = (cacheService.getUserProfile(cacheKey) as? com.maodouchat.server.model.UserResponse)
            ?: userRepository.findByUsername(username)?.also { cacheService.putUserProfile(cacheKey, it) }
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            return@get
        }
        call.respond(buildJsonObject {
            put("ok", true)
            put("user", Json.parseToJsonElement(Json.encodeToString(user)))
        })
    }

    get("/u/{username}") {
        if (!rateLimiter.acquire(call.remoteHost(), maxPerMinute = 60)) {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(buildProfilePage(null, null, "请求过于频繁，请稍后再试"), ContentType.Text.Html)
            return@get
        }
        val username = call.parameters["username"]?.trim()?.lowercase()?.removePrefix("@").orEmpty()
        if (username.isBlank() || username.length < 3) {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(buildProfilePage(null, null, "用户名无效"), ContentType.Text.Html)
            return@get
        }
        val cacheKey = "user_profile:$username"
        val user = (cacheService.getUserProfile(cacheKey) as? com.maodouchat.server.model.UserResponse)
            ?: userRepository.findByUsername(username)?.also { cacheService.putUserProfile(cacheKey, it) }
        if (user == null) {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(buildProfilePage(null, username, "用户不存在"), ContentType.Text.Html)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=60")
        call.respondText(buildProfilePage(user, ServerConfig.baseUrl.trimEnd('/'), null), ContentType.Text.Html)
    }
}
