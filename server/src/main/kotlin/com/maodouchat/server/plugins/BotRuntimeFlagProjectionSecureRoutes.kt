package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.logCommand
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 运行时标志投影·安全。 */
internal fun Route.configureBotRuntimeFlagProjectionSecureRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_RUNTIME_FLAG_PROJECTION_SECURE.forEach { projection ->
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

private val BOT_RUNTIME_FLAG_PROJECTION_SECURE = listOf(
    BotRuntimeFlagProjection(
        "getPrivacySecureFlags",
        listOf("secretChatEnabled", "screenSecureRuntimeEnabled", "captureAlertEnabled", "sealedSenderEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getMediaSendFlags",
        listOf("imageSendEnabled", "videoSendEnabled", "fileShareEnabled", "mediaUploadEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getMediaPrivacyFlags",
        listOf(
            "gifSendEnabled", "blindWatermarkEnabled", "imageSendEnabled", "stickersEnabled",
            "screenSecureRuntimeEnabled", "secretChatEnabled",
        ),
    ),
)
