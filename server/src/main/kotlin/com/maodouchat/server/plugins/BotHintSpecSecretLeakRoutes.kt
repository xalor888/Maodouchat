package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot hint 规范·防泄漏与密钥。 */
internal fun Route.configureBotHintSpecSecretLeakRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    BOT_HINT_SPEC_SECRET_LEAK.forEach { spec ->
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

private val BOT_HINT_SPEC_SECRET_LEAK = listOf(
    BotHintSpec("sendSecretCopyHint", RuntimeConfigService::isSecretCopyBlockEnabled, "secret_copy_block_disabled", "Secret chat copy block can be toggled by admins", "LEAK:COPY "),
    BotHintSpec("sendSecretExportHint", RuntimeConfigService::isSecretMediaExportBlockEnabled, "secret_media_export_block_disabled", "Secret media export block can be toggled by admins", "LEAK:EXPORT "),
    BotHintSpec("sendSecretForwardHint", RuntimeConfigService::isSecretForwardBlockEnabled, "secret_forward_block_disabled", "Secret chat forward block can be toggled by admins", "VAULT:FORWARD "),
    BotHintSpec("sendSecretChatExportHint", RuntimeConfigService::isSecretChatExportBlockEnabled, "secret_chat_export_block_disabled", "Secret chat export block can be toggled by admins", "VAULT:EXPORT "),
    BotHintSpec("sendSealedSenderHint", RuntimeConfigService::isSealedSenderEnabled, "sealed_sender_disabled", "Sealed sender certs hide sender metadata on delivery hops", "SEAL:CERT "),
    BotHintSpec("sendPqxdhHint", RuntimeConfigService::isPqxdhPreviewEnabled, "pqxdh_preview_disabled", "PQXDH preview can be toggled by admins", "SEAL:PQXDH "),
)
