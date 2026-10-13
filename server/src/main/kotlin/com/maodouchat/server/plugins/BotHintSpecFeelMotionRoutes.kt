package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isChatAnimationsEnabled
import com.maodouchat.server.service.isHapticsEnabled
import com.maodouchat.server.service.isInAppSoundsEnabled
import com.maodouchat.server.service.isNavTransitionsEnabled
import com.maodouchat.server.service.isRecentsExclusionEnabled
import com.maodouchat.server.service.isScreenshotDetectEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot hint 规范·体感与动效。 */
internal fun Route.configureBotHintSpecFeelMotionRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    BOT_HINT_SPEC_FEEL_MOTION.forEach { spec ->
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

private val BOT_HINT_SPEC_FEEL_MOTION = listOf(
    BotHintSpec("sendSoundscapeHint", { RuntimeConfigService.isInAppSoundsEnabled() }, "in_app_sounds_disabled", "In-app sounds can be toggled by admins", "FEEL:SOUND "),
    BotHintSpec("sendHapticsHint", { RuntimeConfigService.isHapticsEnabled() }, "haptics_disabled", "Haptics can be toggled by admins", "FEEL:HAPTIC "),
    BotHintSpec("sendMotionHint", { RuntimeConfigService.isChatAnimationsEnabled() }, "chat_animations_disabled", "Chat animations can be toggled by admins", "MOTION:CHAT "),
    BotHintSpec("sendNavHint", { RuntimeConfigService.isNavTransitionsEnabled() }, "nav_transitions_disabled", "Navigation transitions can be toggled by admins", "MOTION:NAV "),
    BotHintSpec("sendCaptureDetectHint", { RuntimeConfigService.isScreenshotDetectEnabled() }, "screenshot_detect_disabled", "Screenshot detection can be toggled by admins", "SHIELD:DETECT "),
    BotHintSpec("sendRecentsHint", { RuntimeConfigService.isRecentsExclusionEnabled() }, "recents_exclusion_disabled", "Recents exclusion can be toggled by admins", "SHIELD:RECENTS "),
)
