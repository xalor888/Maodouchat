package com.maodouchat.server.plugins

import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 探针标志·通知。 */
internal fun Route.configureBotProbeFlagNotifyRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_PROBE_FLAG_NOTIFY.forEach { probe ->
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

private val BOT_PROBE_FLAG_NOTIFY = listOf(
    BotFlagProbe("getNotifyFlags", "getNotifyFlags", 60, listOf(
        flag("unreadPriorityEnabled", RuntimeConfigService::isUnreadPriorityEnabled),
        flag("ringtoneEnabled", RuntimeConfigService::isRingtoneEnabled),
        flag("chatWallpaperEnabled", RuntimeConfigService::isChatWallpaperEnabled),
        flag("chatFontScaleEnabled", RuntimeConfigService::isChatFontScaleEnabled),
    )),
    BotFlagProbe("getAlertMediaFlags", "getAlertMediaFlags", 60, listOf(
        flag("notificationSoundEnabled", RuntimeConfigService::isNotificationSoundEnabled),
        flag("notificationPreviewEnabled", RuntimeConfigService::isNotificationPreviewEnabled),
        flag("unreadPriorityEnabled", RuntimeConfigService::isUnreadPriorityEnabled),
        flag("ringtoneEnabled", RuntimeConfigService::isRingtoneEnabled),
    )),
    BotFlagProbe("getPushFlags", "getPushFlags", 60, listOf(
        flag("pushNotificationsEnabled", RuntimeConfigService::isPushNotificationsEnabled),
        flag("taskRemindersEnabled", RuntimeConfigService::isTaskRemindersEnabled),
        flag("notificationSoundEnabled", RuntimeConfigService::isNotificationSoundEnabled),
        flag("notificationPreviewEnabled", RuntimeConfigService::isNotificationPreviewEnabled),
    )),
    BotFlagProbe("getQuietFlags", "getQuietFlags", 60, listOf(
        flag("dndEnabled", RuntimeConfigService::isDndEnabled),
        flag("pushNotificationsEnabled", RuntimeConfigService::isPushNotificationsEnabled),
        flag("taskRemindersEnabled", RuntimeConfigService::isTaskRemindersEnabled),
    )),
)
