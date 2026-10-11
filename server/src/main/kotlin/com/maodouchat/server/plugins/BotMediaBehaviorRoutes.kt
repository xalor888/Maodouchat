package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 行为：日志上报/更新删除（从 BotMediaProfileRoutes 按域拆出）。 */
internal fun Route.configureBotMediaBehaviorRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

post("/api/bot/logEvent") {
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotLogEventFields(obj)) {
        is BotLogEventFieldsResult.Ok -> parsed.fields
        BotLogEventFieldsResult.InvalidType ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("event, name, chatId and userId must be strings"))
    }
    val chatId = fields.chatId
    val userId = fields.userId
    val event = fields.event
    if (chatId != null && !conversationParticipantRepo.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    if (userId != null && (chatId == null || !conversationParticipantRepo.isParticipant(chatId, userId))) {
        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("user is not in chat"))
    }
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "logEvent:$event")
    call.respond(
    buildJsonObject {
put("ok", true)
put("event", event)
    }
)
}

post("/api/bot/deleteUpdates") {
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
    val body = call.receiveBoundedTextOrEmpty()
    val obj = parseJsonObjectEnvelopeOrNull(body)
    val upTo = when (
        val parsed = parseBotDeleteUpdatesFields(obj, parseRawOrNull(call.request.queryParameters, "upToId"))
    ) {
        is BotDeleteUpdatesFieldsResult.Ok -> parsed.fields.upTo
        BotDeleteUpdatesFieldsResult.Invalid ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("upToId required"))
    }
    val n = com.maodouchat.server.repository.BotRepository.deleteUpdates(bot.id, upTo)
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "deleteUpdates")
    call.respond(
    buildJsonObject {
put("ok", true)
put("deleted", n)
put("upToId", upTo)
    }
)
}
}
