package com.maodouchat.server.plugins

import com.maodouchat.server.repository.getMyCommands
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 命令集查询（getMyCommands）。 */
internal fun Route.configureBotCommandQueryRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        call.respond(
        buildJsonObject {
putJsonElement("commands", commands)
put("count", commands.size)
        }
    )
    }
}
