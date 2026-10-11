package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 运行时标志投影·聊天策略。 */
internal fun Route.configureBotRuntimeFlagProjectionChatRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_RUNTIME_FLAG_PROJECTION_CHAT.forEach { projection ->
        get("/api/bot/${projection.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, projection.path)
            call.respond(buildJsonObject {
                put("ok", true)
                projection.flagNames.forEach { name -> put(name, readBotRuntimeFlag(name)) }
                put("serverTime", System.currentTimeMillis())
            })
        }
    }
}

private val BOT_RUNTIME_FLAG_PROJECTION_CHAT = listOf(
    BotRuntimeFlagProjection(
        "getMuteArchiveFlags",
        BOT_CORE_FLAGS.drop(4) + listOf("chatArchiveEnabled", "chatPinEnabled", "markedUnreadEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getPrivacyFlags",
        BOT_CORE_FLAGS.drop(6) + listOf(
            "disappearingMessagesEnabled", "chatMuteEnabled", "captureAlertEnabled", "sealedSenderEnabled",
        ),
    ),
    BotRuntimeFlagProjection(
        "getMessagePolicyFlags",
        BOT_CORE_FLAGS.drop(8) + listOf(
            "messageEditEnabled", "messageForwardingEnabled", "messageStarringEnabled",
        ),
    ),
    BotRuntimeFlagProjection(
        "getEngagementFlags",
        BOT_CORE_FLAGS.drop(10) + listOf("reactionsEnabled", "groupPlayEnabled", "messageStarringEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getComposerFlags",
        BOT_CORE_FLAGS.drop(12) + listOf("markdownEnabled", "typingIndicatorsEnabled", "aiEnabled"),
    ),
)
