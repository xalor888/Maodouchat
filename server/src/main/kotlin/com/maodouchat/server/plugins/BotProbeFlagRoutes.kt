package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maodouchat.server.service.RuntimeConfigService

internal fun Route.configureBotProbeFlagRoutes(botRateLimiter: BoundedRateLimiter) {

    BOT_FLAG_PROBES.forEach { probe ->
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

private data class BotFlagProbe(
    val path: String,
    val command: String,
    val surface: Int,
    val flags: List<BotBooleanFlag>,
)

private data class BotBooleanFlag(
    val name: String,
    val read: () -> Boolean,
)

private fun flag(name: String, read: () -> Boolean) = BotBooleanFlag(name, read)

private val BOT_FLAG_PROBES = listOf(
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
    BotFlagProbe("getFeelFlags", "getFeelFlags", 60, listOf(
        flag("inAppSoundsEnabled", RuntimeConfigService::isInAppSoundsEnabled),
        flag("hapticsEnabled", RuntimeConfigService::isHapticsEnabled),
        flag("dndEnabled", RuntimeConfigService::isDndEnabled),
    )),
    BotFlagProbe("getMotionFlags", "getMotionFlags", 60, listOf(
        flag("chatAnimationsEnabled", RuntimeConfigService::isChatAnimationsEnabled),
        flag("navTransitionsEnabled", RuntimeConfigService::isNavTransitionsEnabled),
        flag("inAppSoundsEnabled", RuntimeConfigService::isInAppSoundsEnabled),
        flag("hapticsEnabled", RuntimeConfigService::isHapticsEnabled),
    )),
    BotFlagProbe("getCaptureShieldFlags", "getCaptureShieldFlags", 60, listOf(
        flag("screenshotDetectEnabled", RuntimeConfigService::isScreenshotDetectEnabled),
        flag("recentsExclusionEnabled", RuntimeConfigService::isRecentsExclusionEnabled),
        flag("screenSecureRuntimeEnabled", RuntimeConfigService::isScreenSecureRuntimeEnabled),
        flag("captureAlertEnabled", RuntimeConfigService::isCaptureAlertEnabled),
    )),
    BotFlagProbe("getSecretLeakFlags", "getSecretLeakFlags", 60, listOf(
        flag("secretCopyBlockEnabled", RuntimeConfigService::isSecretCopyBlockEnabled),
        flag("secretMediaExportBlockEnabled", RuntimeConfigService::isSecretMediaExportBlockEnabled),
        flag("screenshotDetectEnabled", RuntimeConfigService::isScreenshotDetectEnabled),
        flag("recentsExclusionEnabled", RuntimeConfigService::isRecentsExclusionEnabled),
    )),
    BotFlagProbe("getSecretVaultFlags", "getSecretVaultFlags", 61, listOf(
        flag("secretForwardBlockEnabled", RuntimeConfigService::isSecretForwardBlockEnabled),
        flag("secretChatExportBlockEnabled", RuntimeConfigService::isSecretChatExportBlockEnabled),
        flag("secretCopyBlockEnabled", RuntimeConfigService::isSecretCopyBlockEnabled),
        flag("secretMediaExportBlockEnabled", RuntimeConfigService::isSecretMediaExportBlockEnabled),
    )),
    BotFlagProbe("getSealedCryptoFlags", "getSealedCryptoFlags", 62, listOf(
        flag("sealedSenderEnabled", RuntimeConfigService::isSealedSenderEnabled),
        flag("pqxdhPreview", RuntimeConfigService::isPqxdhPreviewEnabled),
        flag("secretChatEnabled", RuntimeConfigService::isSecretChatEnabled),
    )),
    BotFlagProbe("getMarkPrivacyFlags", "getMarkPrivacyFlags", 63, listOf(
        flag("secretAutoDisappearEnabled", RuntimeConfigService::isSecretAutoDisappearEnabled),
        flag("blindWatermarkEnabled", RuntimeConfigService::isBlindWatermarkEnabled),
    )),
    BotFlagProbe("getLinkPrivacyFlags", "getLinkPrivacyFlags", 64, listOf(
        flag("secretLinkPreviewBlockEnabled", RuntimeConfigService::isSecretLinkPreviewBlockEnabled),
        flag("secretExternalLinkBlockEnabled", RuntimeConfigService::isSecretExternalLinkBlockEnabled),
        flag("linkPreviewEnabled", RuntimeConfigService::isLinkPreviewEnabled),
    )),
    BotFlagProbe("getNotifyPrivacyFlags", "getNotifyPrivacyFlags", 65, listOf(
        flag("secretNotifPreviewBlockEnabled", RuntimeConfigService::isSecretNotifPreviewBlockEnabled),
        flag("secretListPreviewBlockEnabled", RuntimeConfigService::isSecretListPreviewBlockEnabled),
        flag("secretReactionBlockEnabled", RuntimeConfigService::isSecretReactionBlockEnabled),
        flag("secretStarBlockEnabled", RuntimeConfigService::isSecretStarBlockEnabled),
        flag("notificationPreviewEnabled", RuntimeConfigService::isNotificationPreviewEnabled),
    )),
    BotFlagProbe("getSecretMetaFlags", "getSecretMetaFlags", 66, listOf(
        flag("secretReactionBlockEnabled", RuntimeConfigService::isSecretReactionBlockEnabled),
        flag("secretStarBlockEnabled", RuntimeConfigService::isSecretStarBlockEnabled),
        flag("reactionsEnabled", RuntimeConfigService::isReactionsEnabled),
        flag("messageStarringEnabled", RuntimeConfigService::isMessageStarringEnabled),
    )),
    BotFlagProbe("getSecretTypingFlags", "getSecretTypingFlags", 67, listOf(
        flag("secretTypingBlockEnabled", RuntimeConfigService::isSecretTypingBlockEnabled),
        flag("typingIndicatorsEnabled", RuntimeConfigService::isTypingIndicatorsEnabled),
    )),
    BotFlagProbe("getSecretReadReceiptFlags", "getSecretReadReceiptFlags", 68, listOf(
        flag("secretReadReceiptBlockEnabled", RuntimeConfigService::isSecretReadReceiptBlockEnabled),
        flag("readReceiptsEnabled", RuntimeConfigService::isReadReceiptsEnabled),
    )),
    BotFlagProbe("getSecretPresenceFlags", "getSecretPresenceFlags", 69, listOf(
        flag("secretPresenceBlockEnabled", RuntimeConfigService::isSecretPresenceBlockEnabled),
        flag("presenceEnabled", RuntimeConfigService::isPresenceEnabled),
    )),
    BotFlagProbe("getSecretLastSeenFlags", "getSecretLastSeenFlags", 70, listOf(
        flag("secretLastSeenBlockEnabled", RuntimeConfigService::isSecretLastSeenBlockEnabled),
        flag("presenceEnabled", RuntimeConfigService::isPresenceEnabled),
    )),
)
