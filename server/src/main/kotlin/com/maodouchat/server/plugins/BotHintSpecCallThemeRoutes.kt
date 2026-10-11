package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
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

/** Bot hint 规范·通话与主题。 */
internal fun Route.configureBotHintSpecCallThemeRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    BOT_HINT_SPEC_CALL_THEME.forEach { spec ->
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

private val BOT_HINT_SPEC_CALL_THEME = listOf(
    BotHintSpec("sendVoiceCallHint", RuntimeConfigService::isVoiceCallEnabled, "voice_call_disabled", "Voice call can be toggled separately from master calls", "CALL:VOICE "),
    BotHintSpec("sendVideoCallHint", RuntimeConfigService::isVideoCallEnabled, "video_call_disabled", "Video call can be toggled separately from master calls", "CALL:VIDEO "),
    BotHintSpec("sendWallpaperHint", RuntimeConfigService::isChatWallpaperEnabled, "chat_wallpaper_disabled", "Chat wallpaper can be toggled by admins", "THEME:WALL "),
    BotHintSpec("sendFontScaleHint", RuntimeConfigService::isChatFontScaleEnabled, "chat_font_scale_disabled", "Chat font scale can be toggled by admins", "THEME:FONT "),
)
