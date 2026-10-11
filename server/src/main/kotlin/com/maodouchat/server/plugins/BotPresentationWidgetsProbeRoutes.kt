package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 展示·探测：ping 存活与 getMyStats 自检。 */
internal fun Route.configureBotPresentationWidgetsProbeRoutes(
    participantRepository: ConversationParticipantRepository,
    botRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/ping") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "ping")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("ts", System.currentTimeMillis())
        }
    )
    }

    get("/api/bot/getMyStats") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        val chatCount = participantRepository.chatIdsForUser(bot.id).size
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        val cmds = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyStats")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("enabled", bot.enabled)
put("chatCount", chatCount)
put("pendingUpdateCount", pending)
put("commandCount", cmds.size)
put("webhookConfigured", !bot.webhookUrl.isNullOrBlank())
        }
    )
    }
}
