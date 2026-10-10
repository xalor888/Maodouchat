package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 消息变体·命令：命令数查询。 */
internal fun Route.configureBotMessagingCommandRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {

        get("/api/bot/getMyCommandsCount") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
            val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyCommandsCount")
            call.respond(
            buildJsonObject {
    put("botId", bot.id)
    put("count", commands.size)
    putJsonElement("commands", commands)
            }
        )
        }
}
