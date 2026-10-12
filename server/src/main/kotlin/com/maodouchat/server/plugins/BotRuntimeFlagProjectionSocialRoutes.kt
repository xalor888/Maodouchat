package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.logCommand
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 运行时标志投影·社交与媒体。 */
internal fun Route.configureBotRuntimeFlagProjectionSocialRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_RUNTIME_FLAG_PROJECTION_SOCIAL.forEach { projection ->
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

private val BOT_RUNTIME_FLAG_PROJECTION_SOCIAL = listOf(
    BotRuntimeFlagProjection(
        "getSocialFlags",
        BOT_CORE_FLAGS.drop(13) + listOf("friendRequestsEnabled", "nearbyEnabled", "postsEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getIdentityFlags",
        BOT_CORE_FLAGS.drop(17) + listOf("nudgeEnabled", "safetyCodeEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getMediaFlags",
        BOT_CORE_FLAGS.drop(19) + listOf("mediaUploadEnabled", "viewOnceEnabled"),
    ),
    BotRuntimeFlagProjection(
        "getLocationFlags",
        listOf("staticLocationEnabled", "liveLocationEnabled", "fileShareEnabled", "mediaUploadEnabled"),
    ),
)
