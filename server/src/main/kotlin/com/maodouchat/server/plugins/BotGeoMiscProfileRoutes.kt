package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.repository.setMyName
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 资料名片：setMyName。 */
internal fun Route.configureBotGeoMiscProfileRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {
    post("/api/bot/setMyName") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val name = when (val parsed = parseBotSetMyNameFields(obj)) {
            is BotSetMyNameFieldsResult.Ok -> parsed.fields.name
            BotSetMyNameFieldsResult.InvalidName ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid name"))
        }
        val updated = com.maodouchat.server.repository.BotRepository.setMyName(bot.id, name)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyName")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", updated.id)
put("name", updated.name)
        }
    )
    }
}
