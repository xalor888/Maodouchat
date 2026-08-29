package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot Webhook 管理（setWebhook / deleteWebhook）。 */
internal fun Route.configureBotWebhookRoutes(botSendRateLimiter: BoundedRateLimiter) {

    post("/api/bot/setWebhook") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val url = obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)
        if (!url.isNullOrBlank() && !com.maodouchat.server.repository.BotRepository.isAllowedWebhookUrl(url)) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid webhook url"))
        }
        val updated = com.maodouchat.server.repository.BotRepository.setWebhookByToken(bot.id, url)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setWebhook")
        call.respond(
        buildJsonObject {
put("ok", true)
put("url", (updated.webhookUrl ?: ""))
        }
    )
    }

    post("/api/bot/deleteWebhook") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (com.maodouchat.server.repository.BotRepository.setWebhookByToken(bot.id, null) == null) {
            return@post call.respondBotUnavailable()
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "deleteWebhook")
        call.respond(
        buildJsonObject {
put("ok", true)
put("url", "")
        }
    )
    }
}
