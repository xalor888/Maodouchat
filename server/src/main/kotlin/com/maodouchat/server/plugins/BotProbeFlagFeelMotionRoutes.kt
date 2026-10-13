package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isCaptureAlertEnabled
import com.maodouchat.server.service.isChatAnimationsEnabled
import com.maodouchat.server.service.isDndEnabled
import com.maodouchat.server.service.isHapticsEnabled
import com.maodouchat.server.service.isInAppSoundsEnabled
import com.maodouchat.server.service.isNavTransitionsEnabled
import com.maodouchat.server.service.isRecentsExclusionEnabled
import com.maodouchat.server.service.isScreenSecureRuntimeEnabled
import com.maodouchat.server.service.isScreenshotDetectEnabled
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 探针标志·体感与动效。 */
internal fun Route.configureBotProbeFlagFeelMotionRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_PROBE_FLAG_FEEL_MOTION.forEach { probe ->
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

private val BOT_PROBE_FLAG_FEEL_MOTION = listOf(
    BotFlagProbe("getFeelFlags", "getFeelFlags", 60, listOf(
        flag("inAppSoundsEnabled", { RuntimeConfigService.isInAppSoundsEnabled() }),
        flag("hapticsEnabled", { RuntimeConfigService.isHapticsEnabled() }),
        flag("dndEnabled", { RuntimeConfigService.isDndEnabled() }),
    )),
    BotFlagProbe("getMotionFlags", "getMotionFlags", 60, listOf(
        flag("chatAnimationsEnabled", { RuntimeConfigService.isChatAnimationsEnabled() }),
        flag("navTransitionsEnabled", { RuntimeConfigService.isNavTransitionsEnabled() }),
        flag("inAppSoundsEnabled", { RuntimeConfigService.isInAppSoundsEnabled() }),
        flag("hapticsEnabled", { RuntimeConfigService.isHapticsEnabled() }),
    )),
    BotFlagProbe("getCaptureShieldFlags", "getCaptureShieldFlags", 60, listOf(
        flag("screenshotDetectEnabled", { RuntimeConfigService.isScreenshotDetectEnabled() }),
        flag("recentsExclusionEnabled", { RuntimeConfigService.isRecentsExclusionEnabled() }),
        flag("screenSecureRuntimeEnabled", { RuntimeConfigService.isScreenSecureRuntimeEnabled() }),
        flag("captureAlertEnabled", { RuntimeConfigService.isCaptureAlertEnabled() }),
    )),
)
