package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put

/** 开发者账号·Bot 管理：webhook 配置。 */
internal fun Route.configureDeveloperAccountBotWebhookRoutes(
    developerBotSettingsRateLimiter: BoundedRateLimiter,
) {
    put("/bots/{id}/webhook") {
        val userId = devSessionUserId(call)
            ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@put
        if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
            return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
        val body = call.receiveBoundedText().orEmpty()
        val url = parseDeveloperWebhookUrl(body)
        if (!url.isNullOrBlank() && !BotRepository.isAllowedWebhookUrl(url)) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
        }
        // secret is accepted for forward-compat but not persisted (no repo column yet).
        val bot = BotRepository.setWebhook(botId, userId, url)
            ?: return@put call.respondDeveloperBotUnavailable()
        call.respond(bot)
    }
}
