package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bot 命令集（getMyCommands / setMyCommands）。 */
internal fun Route.configureBotCommandRoutes(botSendRateLimiter: BoundedRateLimiter) {

    get("/api/bot/getMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        call.respond(
        buildJsonObject {
put("commands", Json.parseToJsonElement(Json.encodeToString(commands)))
put("count", commands.size)
        }
    )
    }

    post("/api/bot/setMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val arr = obj["commands"] as? kotlinx.serialization.json.JsonArray
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("commands array required"))
        val defs = arr.mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val command = o["command"]?.jsonPrimitive?.content.orEmpty()
            val description = o["description"]?.jsonPrimitive?.content.orEmpty()
            if (command.isBlank() || description.isBlank()) null
            else com.maodouchat.server.repository.BotRepository.BotCommandDef(
                command = command,
                description = description
            )
        }
        val normalized = com.maodouchat.server.repository.BotRepository.normalizeCommands(defs)
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid commands (max 100, unique a-z0-9_, description required)")
            )
        val saved = com.maodouchat.server.repository.BotRepository.setMyCommands(bot.id, normalized)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyCommands")
        call.respond(
        buildJsonObject {
put("ok", true)
put("commands", Json.parseToJsonElement(Json.encodeToString(saved)))
put("count", saved.size)
        }
    )
    }

    post("/api/bot/deleteMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.clearMyCommands(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "deleteMyCommands")
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("commands", buildJsonArray { })
                put("count", 0)
            }
        )
    }
}
