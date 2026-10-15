package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotDto
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.authenticate
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond

/** Resolves the single supported bot credential flow for every bot endpoint. */
internal suspend fun ApplicationCall.requireBot(): BotDto? {
    val token = request.headers["X-Bot-Token"].orEmpty()
        .ifBlank { request.header(HttpHeaders.Authorization).bearerTokenOrNull().orEmpty() }
    val bot = BotRepository.authenticate(token)
    if (bot == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid bot token"))
    }
    return bot
}

internal suspend fun ApplicationCall.acquireBotRequestPermit(
    rateLimiter: BoundedRateLimiter,
    botId: String,
): Boolean {
    if (rateLimiter.acquire(botId, maxPerMinute = 60)) return true
    respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
    return false
}

internal suspend fun ApplicationCall.requireRateLimitedBot(
    rateLimiter: BoundedRateLimiter,
): BotDto? {
    val bot = requireBot() ?: return null
    return if (acquireBotRequestPermit(rateLimiter, bot.id)) bot else null
}

internal suspend fun ApplicationCall.respondBotUnavailable() {
    respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable or disabled", code = "BOT_UNAVAILABLE"))
}
