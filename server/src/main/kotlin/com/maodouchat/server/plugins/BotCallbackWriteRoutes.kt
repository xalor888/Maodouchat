package com.maodouchat.server.plugins

import com.maodouchat.server.repository.logCommand
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 回调应答（answerCallbackQuery）。 */
internal fun Route.configureBotCallbackWriteRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {

    post("/api/bot/answerCallbackQuery") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = parseBotAnswerCallbackQueryFields(obj)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "answerCallbackQuery")
        // Ack only — client shows ephemeral toast via future WS if needed.
        call.respond(
        buildJsonObject {
put("ok", true)
put("callbackQueryId", fields.callbackQueryId)
put("text", (fields.text ?: ""))
        }
    )
    }
}
