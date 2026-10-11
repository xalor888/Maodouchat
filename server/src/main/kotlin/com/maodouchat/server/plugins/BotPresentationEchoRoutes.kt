package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

/** Bot 轻卡片：echo 探针（从 BotPresentationCardsSimpleRoutes 按域拆出）。 */
internal fun Route.configureBotPresentationEchoRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
post("/api/bot/echo") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val text = parseBotEchoFields(obj).text
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "echo")
    call.respond(
    buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("echo", text)
put("serverTime", System.currentTimeMillis())
    }
)
}
}
