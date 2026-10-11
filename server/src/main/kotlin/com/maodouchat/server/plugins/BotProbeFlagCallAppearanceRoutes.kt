package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 探针标志·通话与外观。 */
internal fun Route.configureBotProbeFlagCallAppearanceRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_PROBE_FLAG_CALL_APPEARANCE.forEach { probe ->
        get("/api/bot/${probe.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, probe.command)
            call.respond(buildJsonObject {
                put("ok", true)
                put("botId", bot.id)
                probe.flags.forEach { flag -> put(flag.name, flag.read()) }
                put("surface", probe.surface)
            })
        }
    }
}

private val BOT_PROBE_FLAG_CALL_APPEARANCE = listOf(
    BotFlagProbe("getCallMediaFlags", "getCallMediaFlags", 60, listOf(
        flag("callsEnabled", RuntimeConfigService::isCallsEnabled),
        flag("voiceCallEnabled", RuntimeConfigService::isVoiceCallEnabled),
        flag("videoCallEnabled", RuntimeConfigService::isVideoCallEnabled),
        flag("gifSendEnabled", RuntimeConfigService::isGifSendEnabled),
    )),
    BotFlagProbe("getAppearanceFlags", "getAppearanceFlags", 60, listOf(
        flag("chatWallpaperEnabled", RuntimeConfigService::isChatWallpaperEnabled),
        flag("chatFontScaleEnabled", RuntimeConfigService::isChatFontScaleEnabled),
        flag("voiceCallEnabled", RuntimeConfigService::isVoiceCallEnabled),
        flag("videoCallEnabled", RuntimeConfigService::isVideoCallEnabled),
    )),
)
