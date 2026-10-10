package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put

internal fun Route.configureBotManagementSettingsRoutes(
    userRepo: UserRepository,
    botTokenRateLimiter: BoundedRateLimiter,
) {
    post("/api/bots/{botId}/token") {
        if (call.rejectIfMaintenance()) return@post
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        // token 轮换限流：防高频轮换刷 DB 写
        if (!botTokenRateLimiter.acquire(userId, maxPerMinute = 10)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("botId", "missing botId") ?: return@post
        val bot = com.maodouchat.server.repository.BotRepository.regenerateToken(botId, userId)
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        call.respond(bot)
    }
    put("/api/bots/{botId}/webhook") {
        if (call.rejectIfMaintenance()) return@put
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@put
        val botId = call.requirePathParamOr400("botId", "missing botId") ?: return@put
        val body = call.receiveBoundedTextOrEmpty()
        val url = parseManagementWebhookUrl(body)
        if (!url.isNullOrBlank() && !com.maodouchat.server.repository.BotRepository.isAllowedWebhookUrl(url)) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
        }
        val bot = com.maodouchat.server.repository.BotRepository.setWebhook(botId, userId, url)
            ?: return@put call.respondBotUnavailable()
        call.respond(bot)
    }
    put("/api/bots/{botId}/enabled") {
        if (call.rejectIfMaintenance()) return@put
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@put
        val botId = call.requirePathParamOr400("botId", "missing botId") ?: return@put
        val body = call.receiveBoundedTextOrEmpty()
        val enabled = parseManagementBotEnabled(body)
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
        val bot = com.maodouchat.server.repository.BotRepository.setEnabled(botId, userId, enabled)
            ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        call.respond(bot)
    }
}
