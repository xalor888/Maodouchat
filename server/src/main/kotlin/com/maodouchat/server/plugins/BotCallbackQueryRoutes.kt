package com.maodouchat.server.plugins

import com.maodouchat.server.repository.getUpdates
import com.maodouchat.server.repository.logCommand
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 更新轮询（getUpdates）。 */
internal fun Route.configureBotCallbackQueryRoutes(
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getUpdates") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val offset = parseAdminListOffset(call.request.queryParameters)
        val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
        val rows = com.maodouchat.server.repository.BotRepository.getUpdates(bot.id, offset, limit)
        // 8.46：混合类型 List<Map<String,Any>> 经 Json.encodeToString 运行时抛
        // SerializationException（Serializer for class 'Any' is not found）→ 端点 500。
        // 改用 buildJsonArray 直接构造 JSON 元素。
        val updatesArray = buildJsonArray {
            rows.forEach { (id, jsonStr) ->
                add(buildJsonObject {
                    put("update_id", id)
                    put(
                        "payload",
                        runCatching { Json.parseToJsonElement(jsonStr) }
                            .getOrElse { kotlinx.serialization.json.JsonPrimitive(jsonStr) }
                    )
                })
            }
        }
        val nextOffset = rows.lastOrNull()?.first?.plus(1) ?: offset
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getUpdates")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updates", updatesArray)
put("nextOffset", nextOffset)
        }
    )
    }
}
