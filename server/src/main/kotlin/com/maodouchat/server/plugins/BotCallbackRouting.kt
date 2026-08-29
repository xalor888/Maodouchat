package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 回调应答与更新轮询（answerCallbackQuery / getUpdates）。 */
internal fun Route.configureBotCallbackRoutes(botSendRateLimiter: BoundedRateLimiter) {

    post("/api/bot/answerCallbackQuery") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val callbackQueryId = obj["callbackQueryId"]?.jsonPrimitive?.content
            ?: obj["id"]?.jsonPrimitive?.content
            ?: ""
        val text = obj["text"]?.jsonPrimitive?.content?.take(200)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "answerCallbackQuery")
        // Ack only — client shows ephemeral toast via future WS if needed.
        call.respond(
        buildJsonObject {
put("ok", true)
put("callbackQueryId", callbackQueryId)
put("text", (text ?: ""))
        }
    )
    }

    get("/api/bot/getUpdates") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
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
