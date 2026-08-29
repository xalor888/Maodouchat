package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registers compatibility probes from data instead of one route implementation per name. */
internal fun Route.configureBotProbeRoutes(botRateLimiter: BoundedRateLimiter) {
    BOT_PING_PROBES.forEach { probe ->
        get("/api/bot/${probe.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, probe.command)
            call.respond(buildJsonObject {
                put("ok", true)
                put("botId", bot.id)
                put("surface", probe.surface)
                put("ping", probe.value)
            })
        }
    }
    BOT_BOOLEAN_PROBES.forEach { probe ->
        get("/api/bot/${probe.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, probe.command)
            call.respond(buildJsonObject {
                put("ok", true)
                put(probe.signal, true)
                put("botId", bot.id)
                put("surface", probe.surface)
                put("serverTime", System.currentTimeMillis())
            })
        }
    }
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

private data class BotPingProbe(
    val path: String,
    val command: String,
    val surface: Int,
    val value: String,
)

private data class BotBooleanProbe(
    val path: String,
    val command: String,
    val signal: String,
    val surface: Int,
)

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

private val BOT_PING_PROBES = listOf(
    BotPingProbe("buzzz", "buzzz", 60, "buzz"),
    BotPingProbe("chimez", "chimez", 60, "chime"),
    BotPingProbe("ringz", "ringz", 60, "ring"),
    BotPingProbe("beepz", "beepz", 60, "beep"),
    BotPingProbe("pushz", "pushz", 60, "push"),
    BotPingProbe("quietz", "quietz", 60, "quiet"),
    BotPingProbe("fealz", "fealz", 60, "feel"),
    BotPingProbe("slidez", "slidez", 60, "slide"),
    BotPingProbe("leakz", "leakz", 60, "leak"),
    BotPingProbe("vaultz", "vaultz", 61, "vault"),
    BotPingProbe("sealz", "sealz", 62, "seal"),
    BotPingProbe("markz", "markz", 63, "mark"),
    BotPingProbe("linkz", "linkz", 64, "link"),
    BotPingProbe("privz", "privz", 65, "priv"),
    BotPingProbe("metaz", "metaz", 66, "meta"),
    BotPingProbe("typtz", "typtz", 67, "typing"),
    BotPingProbe("redz", "redz", 68, "read"),
    BotPingProbe("presz", "presz", 69, "presence"),
    BotPingProbe("lastsz", "lastsz", 70, "lastseen"),
)

private val BOT_BOOLEAN_PROBES = listOf(
    BotBooleanProbe("readyz", "readyz", "ready", 60),
    BotBooleanProbe("alivez", "alivez", "alive", 60),
    BotBooleanProbe("heartbeatz", "heartbeatz", "heartbeat", 60),
    BotBooleanProbe("pulsez", "pulsez", "pulse", 60),
    BotBooleanProbe("tickz", "tickz", "tick", 60),
    BotBooleanProbe("tockz", "tockz", "tock", 60),
    BotBooleanProbe("clangz", "clangz", "clang", 60),
    BotBooleanProbe("dingz", "dingz", "ding", 60),
)

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
