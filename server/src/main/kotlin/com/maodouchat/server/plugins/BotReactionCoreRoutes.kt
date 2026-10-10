package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 消息回应：setMessageReaction。 */
internal fun Route.configureBotReactionCoreRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    post("/api/bot/setMessageReaction") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isReactionsEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("reactions_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotReactionFields(obj)) {
            is BotReactionFieldsResult.Ok -> parsed.fields
            BotReactionFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/emoji required"))
            BotReactionFieldsResult.UnsupportedEmoji ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("unsupported emoji"))
        }
        val messageId = fields.messageId
        val emoji = fields.emoji
        val msg = serviceMessageRepo.metadata(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val botReactions = serviceMessageRepo.setReaction(messageId, bot.id, emoji)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot react"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, messageId, "setMessageReaction")
        fanoutBotEvent(
            userRepo = userRepo,
            participantRepository = conversationParticipantRepo,
            json = json,
            botId = bot.id,
            chatId = msg.chatId,
            event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                action = "REACTION_SET",
                targetMessageId = messageId,
                reactionEmoji = emoji,
            ),
            messagingV2Repository = messagingV2Repository,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("emoji", emoji)
putJsonElement("reactions", botReactions)
        }
    )
    }
}
