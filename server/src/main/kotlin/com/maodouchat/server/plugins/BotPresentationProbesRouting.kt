package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
/** Bot 版本/健康探针（自 BotPresentationCardsRouting.kt 拆分）。 */
internal fun Route.configureBotPresentationProbesRoutes(
    botRateLimiter: BoundedRateLimiter,
) {

        get("/api/bot/getVersion") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getVersion")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("api", "maodouchat-bot")
    put("surface", 34)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/healthz") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "healthz")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("status", "up")
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/uptime") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "uptime")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("serverTime", System.currentTimeMillis())
    put("surface", 39)
            }
        )
        }

        get("/api/bot/echoTime") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "echoTime")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("serverTime", System.currentTimeMillis())
    put("surface", 39)
            }
        )
        }

        get("/api/bot/versionz") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "versionz")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("surface", 60)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/getTrustFlags") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getTrustFlags")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("nudgeEnabled", com.maodouchat.server.service.RuntimeConfigService.isNudgeEnabled())
    put("safetyCodeEnabled", com.maodouchat.server.service.RuntimeConfigService.isSafetyCodeEnabled())
    put("qrCodeEnabled", com.maodouchat.server.service.RuntimeConfigService.isQrCodeEnabled())
    put("contactCardEnabled", com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled())
    put("spoilerMediaEnabled", com.maodouchat.server.service.RuntimeConfigService.isSpoilerMediaEnabled())
    put("autoDownloadEnabled", com.maodouchat.server.service.RuntimeConfigService.isAutoDownloadEnabled())
    put("staticLocationEnabled", com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled())
    put("fileShareEnabled", com.maodouchat.server.service.RuntimeConfigService.isFileShareEnabled())
    put("secretChatEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretChatEnabled())
    put("screenSecureRuntimeEnabled", com.maodouchat.server.service.RuntimeConfigService.isScreenSecureRuntimeEnabled())
    put("imageSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled())
    put("videoSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isVideoSendEnabled())
    put("gifSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isGifSendEnabled())
    put("blindWatermarkEnabled", com.maodouchat.server.service.RuntimeConfigService.isBlindWatermarkEnabled())
    put("voiceCallEnabled", com.maodouchat.server.service.RuntimeConfigService.isVoiceCallEnabled())
    put("videoCallEnabled", com.maodouchat.server.service.RuntimeConfigService.isVideoCallEnabled())
    put("chatWallpaperEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatWallpaperEnabled())
    put("chatFontScaleEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatFontScaleEnabled())
    put("unreadPriorityEnabled", com.maodouchat.server.service.RuntimeConfigService.isUnreadPriorityEnabled())
    put("ringtoneEnabled", com.maodouchat.server.service.RuntimeConfigService.isRingtoneEnabled())
    put("notificationSoundEnabled", com.maodouchat.server.service.RuntimeConfigService.isNotificationSoundEnabled())
    put("notificationPreviewEnabled", com.maodouchat.server.service.RuntimeConfigService.isNotificationPreviewEnabled())
    put("pushNotificationsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPushNotificationsEnabled())
    put("taskRemindersEnabled", com.maodouchat.server.service.RuntimeConfigService.isTaskRemindersEnabled())
    put("dndEnabled", com.maodouchat.server.service.RuntimeConfigService.isDndEnabled())
    put("inAppSoundsEnabled", com.maodouchat.server.service.RuntimeConfigService.isInAppSoundsEnabled())
    put("hapticsEnabled", com.maodouchat.server.service.RuntimeConfigService.isHapticsEnabled())
    put("chatAnimationsEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatAnimationsEnabled())
    put("navTransitionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isNavTransitionsEnabled())
    put("screenshotDetectEnabled", com.maodouchat.server.service.RuntimeConfigService.isScreenshotDetectEnabled())
    put("recentsExclusionEnabled", com.maodouchat.server.service.RuntimeConfigService.isRecentsExclusionEnabled())
    put("secretCopyBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretCopyBlockEnabled())
    put("secretMediaExportBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretMediaExportBlockEnabled())
    put("secretForwardBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretForwardBlockEnabled())
    put("secretChatExportBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretChatExportBlockEnabled())
    put("secretAutoDisappearEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretAutoDisappearEnabled())
    put("secretLinkPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
    put("secretExternalLinkBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretExternalLinkBlockEnabled())
    put("secretNotifPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
    put("secretListPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretListPreviewBlockEnabled())
    put("secretReactionBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretReactionBlockEnabled())
    put("secretStarBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretStarBlockEnabled())
    put("captureAlertEnabled", com.maodouchat.server.service.RuntimeConfigService.isCaptureAlertEnabled())
    put("sealedSenderEnabled", com.maodouchat.server.service.RuntimeConfigService.isSealedSenderEnabled())
    put("forceE2eeBanner", com.maodouchat.server.service.RuntimeConfigService.get(com.maodouchat.server.service.RuntimeConfigService.KEY_FORCE_E2EE_BANNER))
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/statusz") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "statusz")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("status", "up")
    put("botId", bot.id)
    put("surface", 60)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }
}
