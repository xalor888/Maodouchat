package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot webhook 探针（getWebhookInfo）。 */
internal fun Route.configureBotInfoWebhookRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/webhookInfo") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "webhookInfo")
        call.respond(
        buildJsonObject {
put("url", (bot.webhookUrl ?: ""))
put("hasCustomCertificate", false)
put("pendingUpdateCount", pending)
put("signed", true)
put("signatureHeader", "X-Maodouchat-Signature")
put("maxConnections", 40)
        }
    )
    }
}
