package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 地理·位置消息：位置/场地。 */
internal fun Route.configureBotGeoLocationRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    serviceMessageRepo: ServiceMessageRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

        post("/api/bot/sendLocation") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("static_location_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val chatId: String
            val lat: Double
            val lon: Double
            val title: String
            when (val r = parseBotSendLocationFields(obj)) {
                is BotSendLocationFieldsResult.Ok -> {
                    chatId = r.fields.chatId
                    lat = r.fields.latitude
                    lon = r.fields.longitude
                    title = r.fields.title
                }
                BotSendLocationFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/latitude/longitude required"))
                BotSendLocationFieldsResult.InvalidCoordinates ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid coordinates"))
            }
            // 校验顺序与原处理器一致（必填 → 坐标范围在纯函数里 → 成员检查在处理器里）
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            // Bot plaintext location marker（组装逻辑收敛至 buildBotLocationContent，逐字一致）。
            val content = buildBotLocationContent(lat, lon, title)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "LOCATION")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendLocation")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "LOCATION", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("latitude", lat)
    put("longitude", lon)
            }
        )
        }

        post("/api/bot/sendVenue") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendVenueFields(obj)) {
                is BotSendVenueFieldsResult.Ok -> parsed.fields
                BotSendVenueFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/latitude/longitude/title required"))
                BotSendVenueFieldsResult.InvalidCoordinates ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid coordinates"))
            }
            val chatId = fields.chatId
            val lat = fields.latitude
            val lon = fields.longitude
            val title = fields.title
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotVenueContent(lat, lon, title, fields.address)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "LOCATION")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVenue")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "LOCATION", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("latitude", lat)
    put("longitude", lon)
    put("title", title)
            }
        )
        }
}
