package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 投票测验/骰子域只读查询端点（自 BotPollQuizRouting.kt 拆分）。 */
internal fun Route.configureBotPollQuizInfoRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getWebhookInfo") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getWebhookInfo")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("url", (bot.webhookUrl ?: ""))
put("hasCustomCertificate", false)
put("pendingUpdateCount", pending)
put("maxConnections", 40)
put("enabled", bot.enabled)
        }
    )
    }
}
