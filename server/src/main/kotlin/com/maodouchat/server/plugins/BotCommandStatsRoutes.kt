package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 指令统计：getCommandStats。 */
internal fun Route.configureBotCommandStatsRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {
    get("/api/bot/getCommandStats") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val limit = parseAdminListLimit(call.request.queryParameters)
        val logs = com.maodouchat.server.repository.BotRepository.listCommandLogs(bot.id, limit)
        val counts = linkedMapOf<String, Int>()
        logs.forEach { row ->
            val c = row.command.ifBlank { "?" }
            counts[c] = (counts[c] ?: 0) + 1
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getCommandStats")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("totalSampled", logs.size)
putJsonElement("byCommand", counts)
put("recent", buildJsonArray {
    logs.take(20).forEach {
add(buildJsonObject {
    put("id", it.id)
    put("command", it.command)
    put("chatId", (it.chatId ?: ""))
    put("createdAt", it.createdAt)
})
    }
})
        }
    )
    }
}
