package com.maodouchat.server.plugins

import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.service.isAutoDownloadEnabled
import com.maodouchat.server.service.isBlindWatermarkEnabled
import com.maodouchat.server.service.isCaptureAlertEnabled
import com.maodouchat.server.service.isChatAnimationsEnabled
import com.maodouchat.server.service.isChatFontScaleEnabled
import com.maodouchat.server.service.isChatWallpaperEnabled
import com.maodouchat.server.service.isContactCardEnabled
import com.maodouchat.server.service.isDndEnabled
import com.maodouchat.server.service.isFileShareEnabled
import com.maodouchat.server.service.isGifSendEnabled
import com.maodouchat.server.service.isHapticsEnabled
import com.maodouchat.server.service.isImageSendEnabled
import com.maodouchat.server.service.isInAppSoundsEnabled
import com.maodouchat.server.service.isNavTransitionsEnabled
import com.maodouchat.server.service.isNotificationPreviewEnabled
import com.maodouchat.server.service.isNotificationSoundEnabled
import com.maodouchat.server.service.isNudgeEnabled
import com.maodouchat.server.service.isPushNotificationsEnabled
import com.maodouchat.server.service.isQrCodeEnabled
import com.maodouchat.server.service.isRecentsExclusionEnabled
import com.maodouchat.server.service.isRingtoneEnabled
import com.maodouchat.server.service.isSafetyCodeEnabled
import com.maodouchat.server.service.isScreenSecureRuntimeEnabled
import com.maodouchat.server.service.isScreenshotDetectEnabled
import com.maodouchat.server.service.isSealedSenderEnabled
import com.maodouchat.server.service.isSecretAutoDisappearEnabled
import com.maodouchat.server.service.isSecretChatEnabled
import com.maodouchat.server.service.isSecretChatExportBlockEnabled
import com.maodouchat.server.service.isSecretCopyBlockEnabled
import com.maodouchat.server.service.isSecretExternalLinkBlockEnabled
import com.maodouchat.server.service.isSecretForwardBlockEnabled
import com.maodouchat.server.service.isSecretLinkPreviewBlockEnabled
import com.maodouchat.server.service.isSecretListPreviewBlockEnabled
import com.maodouchat.server.service.isSecretMediaExportBlockEnabled
import com.maodouchat.server.service.isSecretNotifPreviewBlockEnabled
import com.maodouchat.server.service.isSecretReactionBlockEnabled
import com.maodouchat.server.service.isSecretStarBlockEnabled
import com.maodouchat.server.service.isSpoilerMediaEnabled
import com.maodouchat.server.service.isStaticLocationEnabled
import com.maodouchat.server.service.isTaskRemindersEnabled
import com.maodouchat.server.service.isUnreadPriorityEnabled
import com.maodouchat.server.service.isVideoCallEnabled
import com.maodouchat.server.service.isVideoSendEnabled
import com.maodouchat.server.service.isVoiceCallEnabled
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 展示·能力开关：getTrustFlags 客户端特性开关查询。 */
internal fun Route.configureBotPresentationProbesFlagsRoutes(
    botRateLimiter: BoundedRateLimiter,
) {

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
}
