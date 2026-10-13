package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isSecretAutoDisappearEnabled
import com.maodouchat.server.service.isSecretExternalLinkBlockEnabled
import com.maodouchat.server.service.isSecretLastSeenBlockEnabled
import com.maodouchat.server.service.isSecretLinkPreviewBlockEnabled
import com.maodouchat.server.service.isSecretListPreviewBlockEnabled
import com.maodouchat.server.service.isSecretNotifPreviewBlockEnabled
import com.maodouchat.server.service.isSecretPresenceBlockEnabled
import com.maodouchat.server.service.isSecretReactionBlockEnabled
import com.maodouchat.server.service.isSecretReadReceiptBlockEnabled
import com.maodouchat.server.service.isSecretStarBlockEnabled
import com.maodouchat.server.service.isSecretTypingBlockEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot hint 规范·隐私元数据。 */
internal fun Route.configureBotHintSpecSecretPrivacyRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    BOT_HINT_SPEC_SECRET_PRIVACY.forEach { spec ->
        post("/api/bot/${spec.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!spec.enabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(spec.disabledError))
                return@post
            }
            val body = call.receiveBoundedTextOrEmpty()
            val request = call.requireJsonObjectOr400(body) ?: return@post
            val chatId = when (val parsed = parseBotHintChatId(request)) {
                is BotHintChatIdResult.Ok -> parsed.chatId
                BotHintChatIdResult.Invalid ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
            }
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
                return@post
            }
            val hint = resolveBotHint(request, spec.sanitize, spec.defaultHint)
            val content = spec.contentPrefix + hint
            val messageId = "bot_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val message = runCatching {
                publishBotServiceMessage(
                    userRepository = userRepository,
                    participantRepository = participantRepository,
                    serviceMessageRepository = serviceMessageRepository,
                    json = json,
                    botId = bot.id,
                    chatId = chatId,
                    messageId = messageId,
                    content = content,
                    timestamp = now,
                    type = "SYSTEM",
                )
            }.getOrNull()
            if (message == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
                return@post
            }
            BotRepository.logCommand(bot.id, chatId, null, spec.path)
            call.respond(buildJsonObject {
                put("ok", true)
                put("messageId", messageId)
                put("type", "SYSTEM")
            })
        }
    }
}

private val BOT_HINT_SPEC_SECRET_PRIVACY = listOf(
    BotHintSpec("sendSecretAutoDisappearHint", { RuntimeConfigService.isSecretAutoDisappearEnabled() }, "secret_auto_disappear_disabled", "Secret chats auto-enable 24h disappearing when timer is off", "MARK:AUTO24H "),
    BotHintSpec("sendSecretLinkPreviewHint", { RuntimeConfigService.isSecretLinkPreviewBlockEnabled() }, "secret_link_preview_block_disabled", "Secret chats block link previews to avoid external fetches", "LINK:PREVIEW "),
    BotHintSpec("sendSecretExternalLinkHint", { RuntimeConfigService.isSecretExternalLinkBlockEnabled() }, "secret_external_link_block_disabled", "Secret chats can block opening external links", "LINK:EXTERNAL "),
    BotHintSpec("sendSecretNotifPreviewHint", { RuntimeConfigService.isSecretNotifPreviewBlockEnabled() }, "secret_notif_preview_block_disabled", "Secret chat notifications hide sender and body previews", "NOTIFY:SECRET "),
    BotHintSpec("sendSecretListPreviewHint", { RuntimeConfigService.isSecretListPreviewBlockEnabled() }, "secret_list_preview_block_disabled", "Secret chats hide message previews in the chat list", "NOTIFY:LIST "),
    BotHintSpec("sendSecretReactionHint", { RuntimeConfigService.isSecretReactionBlockEnabled() }, "secret_reaction_block_disabled", "Secret chats block reactions to reduce metadata leaks", "META:REACT ", sanitize = true),
    BotHintSpec("sendSecretStarHint", { RuntimeConfigService.isSecretStarBlockEnabled() }, "secret_star_block_disabled", "Secret chats block starring to avoid cloud-side favorites metadata", "META:STAR ", sanitize = true),
    BotHintSpec("sendSecretTypingHint", { RuntimeConfigService.isSecretTypingBlockEnabled() }, "secret_typing_block_disabled", "Secret chats block typing indicators to avoid presence side-channel", "META:TYPING ", sanitize = true),
    BotHintSpec("sendSecretReadReceiptHint", { RuntimeConfigService.isSecretReadReceiptBlockEnabled() }, "secret_read_receipt_block_disabled", "Secret chats block read receipts to avoid observation side-channel", "META:READ ", sanitize = true),
    BotHintSpec("sendSecretPresenceHint", { RuntimeConfigService.isSecretPresenceBlockEnabled() }, "secret_presence_block_disabled", "Secret chats block presence/online to avoid online side-channel", "META:PRESENCE ", sanitize = true),
    BotHintSpec("sendSecretLastSeenHint", { RuntimeConfigService.isSecretLastSeenBlockEnabled() }, "secret_last_seen_block_disabled", "Secret chats block last seen to avoid observation side-channel", "META:LASTSEEN ", sanitize = true),
)
