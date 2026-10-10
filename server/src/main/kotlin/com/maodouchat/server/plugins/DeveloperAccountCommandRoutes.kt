package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 开发者账号·命令菜单：读写 bot 命令菜单。 */
internal fun Route.configureDeveloperAccountCommandRoutes(
    developerBotSettingsRateLimiter: BoundedRateLimiter,
) {

        put("/bots/{id}/commands") {
            val userId = devSessionUserId(call)
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            if (call.rejectIfDeveloperMaintenance()) return@put
            if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
                return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
            }
            val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
            if (devSessionOwnedBot(botId, userId) == null) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该机器人"))
            }
            val body = call.receiveBoundedText().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@put
            // 8.48 以来逐项严格校验，任一条目非法即 400（禁止静默丢弃后误清空菜单）；
            // 仅显式传空数组 = 合法清空。抽取逻辑见 parseDeveloperBotCommands。
            val defs = when (val parsed = parseDeveloperBotCommands(obj)) {
                is DeveloperBotCommandsParseResult.Ok -> parsed.defs.map { (command, description) ->
                    BotRepository.BotCommandDef(command = command, description = description)
                }
                is DeveloperBotCommandsParseResult.Invalid -> return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(parsed.message)
                )
            }
            val normalized = BotRepository.normalizeCommands(defs)
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid commands (max 100, unique a-z0-9_, description required)")
                )
            val saved = BotRepository.setMyCommands(botId, normalized)
                ?: return@put call.respondDeveloperBotUnavailable()
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("commands", saved)
    put("count", saved.size)
            }
        )
        }

        get("/bots/{id}/commands") {
            val userId = devSessionUserId(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            val botId = call.requirePathParamOr400("id", "missing botId") ?: return@get
            if (devSessionOwnedBot(botId, userId) == null) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该机器人"))
            }
            val commands = BotRepository.getMyCommands(botId)
            call.respond(
            buildJsonObject {
    putJsonElement("commands", commands)
    put("count", commands.size)
            }
        )
        }
}
