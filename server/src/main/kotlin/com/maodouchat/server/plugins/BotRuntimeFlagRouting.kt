package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Runtime-flag projections backed by one ordered catalog instead of copied response builders. */
internal fun Route.configureBotRuntimeFlagRoutes(botRateLimiter: BoundedRateLimiter) {
    BOT_RUNTIME_FLAG_PROJECTIONS.forEach { projection ->
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

private data class BotRuntimeFlagProjection(
    val path: String,
    val flagNames: List<String>,
)

private val BOT_CORE_FLAGS = listOf(
    "chatArchiveEnabled", "nearbyEnabled", "chatPinEnabled", "markedUnreadEnabled",
    "chatMuteEnabled", "disappearingMessagesEnabled", "chatLockEnabled", "messageEditEnabled",
    "messagePinEnabled", "messageRevokeEnabled", "pollsEnabled", "appLockEnabled",
    "chatDraftsEnabled", "groupInvitesEnabled", "mentionsEnabled", "nudgeEnabled",
    "safetyCodeEnabled", "qrCodeEnabled", "contactCardEnabled", "spoilerMediaEnabled",
    "autoDownloadEnabled", "staticLocationEnabled", "fileShareEnabled", "secretChatEnabled",
    "screenSecureRuntimeEnabled", "imageSendEnabled", "videoSendEnabled", "gifSendEnabled",
    "blindWatermarkEnabled", "voiceCallEnabled", "videoCallEnabled", "chatWallpaperEnabled",
    "chatFontScaleEnabled", "unreadPriorityEnabled", "ringtoneEnabled", "notificationSoundEnabled",
    "notificationPreviewEnabled", "pushNotificationsEnabled", "taskRemindersEnabled", "dndEnabled",
    "inAppSoundsEnabled", "hapticsEnabled", "chatAnimationsEnabled", "navTransitionsEnabled",
    "screenshotDetectEnabled", "recentsExclusionEnabled", "secretCopyBlockEnabled",
    "secretMediaExportBlockEnabled", "secretForwardBlockEnabled", "secretChatExportBlockEnabled",
    "secretAutoDisappearEnabled", "secretLinkPreviewBlockEnabled", "secretExternalLinkBlockEnabled",
    "secretNotifPreviewBlockEnabled", "secretListPreviewBlockEnabled", "secretReactionBlockEnabled",
    "secretStarBlockEnabled",
)

private val BOT_RUNTIME_FLAG_PROJECTIONS = listOf(
    BotRuntimeFlagProjection(
        "getRuntimeFlags",
        BOT_CORE_FLAGS + listOf("postsEnabled", "blockReportEnabled", "markdownEnabled", "groupPlayEnabled"),
    ),
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

private fun readBotRuntimeFlag(name: String): Boolean = when (name) {
    "aiEnabled" -> RuntimeConfigService.isAiEnabled()
    "appLockEnabled" -> RuntimeConfigService.isAppLockEnabled()
    "autoDownloadEnabled" -> RuntimeConfigService.isAutoDownloadEnabled()
    "blindWatermarkEnabled" -> RuntimeConfigService.isBlindWatermarkEnabled()
    "blockReportEnabled" -> RuntimeConfigService.isBlockReportEnabled()
    "captureAlertEnabled" -> RuntimeConfigService.isCaptureAlertEnabled()
    "chatAnimationsEnabled" -> RuntimeConfigService.isChatAnimationsEnabled()
    "chatArchiveEnabled" -> RuntimeConfigService.isChatArchiveEnabled()
    "chatDraftsEnabled" -> RuntimeConfigService.isChatDraftsEnabled()
    "chatFontScaleEnabled" -> RuntimeConfigService.isChatFontScaleEnabled()
    "chatLockEnabled" -> RuntimeConfigService.isChatLockEnabled()
    "chatMuteEnabled" -> RuntimeConfigService.isChatMuteEnabled()
    "chatPinEnabled" -> RuntimeConfigService.isChatPinEnabled()
    "chatWallpaperEnabled" -> RuntimeConfigService.isChatWallpaperEnabled()
    "contactCardEnabled" -> RuntimeConfigService.isContactCardEnabled()
    "disappearingMessagesEnabled" -> RuntimeConfigService.isDisappearingMessagesEnabled()
    "dndEnabled" -> RuntimeConfigService.isDndEnabled()
    "fileShareEnabled" -> RuntimeConfigService.isFileShareEnabled()
    "friendRequestsEnabled" -> RuntimeConfigService.isFriendRequestsEnabled()
    "gifSendEnabled" -> RuntimeConfigService.isGifSendEnabled()
    "groupInvitesEnabled" -> RuntimeConfigService.isGroupInvitesEnabled()
    "groupPlayEnabled" -> RuntimeConfigService.isGroupPlayEnabled()
    "hapticsEnabled" -> RuntimeConfigService.isHapticsEnabled()
    "imageSendEnabled" -> RuntimeConfigService.isImageSendEnabled()
    "inAppSoundsEnabled" -> RuntimeConfigService.isInAppSoundsEnabled()
    "liveLocationEnabled" -> RuntimeConfigService.isLiveLocationEnabled()
    "markdownEnabled" -> RuntimeConfigService.isMarkdownEnabled()
    "markedUnreadEnabled" -> RuntimeConfigService.isMarkedUnreadEnabled()
    "mediaUploadEnabled" -> RuntimeConfigService.isMediaUploadEnabled()
    "mentionsEnabled" -> RuntimeConfigService.isMentionsEnabled()
    "messageEditEnabled" -> RuntimeConfigService.isMessageEditEnabled()
    "messageForwardingEnabled" -> RuntimeConfigService.isMessageForwardingEnabled()
    "messagePinEnabled" -> RuntimeConfigService.isMessagePinEnabled()
    "messageRevokeEnabled" -> RuntimeConfigService.isMessageRevokeEnabled()
    "messageStarringEnabled" -> RuntimeConfigService.isMessageStarringEnabled()
    "navTransitionsEnabled" -> RuntimeConfigService.isNavTransitionsEnabled()
    "nearbyEnabled" -> RuntimeConfigService.isNearbyEnabled()
    "notificationPreviewEnabled" -> RuntimeConfigService.isNotificationPreviewEnabled()
    "notificationSoundEnabled" -> RuntimeConfigService.isNotificationSoundEnabled()
    "nudgeEnabled" -> RuntimeConfigService.isNudgeEnabled()
    "pollsEnabled" -> RuntimeConfigService.isPollsEnabled()
    "postsEnabled" -> RuntimeConfigService.isPostsEnabled()
    "pushNotificationsEnabled" -> RuntimeConfigService.isPushNotificationsEnabled()
    "qrCodeEnabled" -> RuntimeConfigService.isQrCodeEnabled()
    "reactionsEnabled" -> RuntimeConfigService.isReactionsEnabled()
    "recentsExclusionEnabled" -> RuntimeConfigService.isRecentsExclusionEnabled()
    "ringtoneEnabled" -> RuntimeConfigService.isRingtoneEnabled()
    "safetyCodeEnabled" -> RuntimeConfigService.isSafetyCodeEnabled()
    "screenSecureRuntimeEnabled" -> RuntimeConfigService.isScreenSecureRuntimeEnabled()
    "screenshotDetectEnabled" -> RuntimeConfigService.isScreenshotDetectEnabled()
    "sealedSenderEnabled" -> RuntimeConfigService.isSealedSenderEnabled()
    "secretAutoDisappearEnabled" -> RuntimeConfigService.isSecretAutoDisappearEnabled()
    "secretChatEnabled" -> RuntimeConfigService.isSecretChatEnabled()
    "secretChatExportBlockEnabled" -> RuntimeConfigService.isSecretChatExportBlockEnabled()
    "secretCopyBlockEnabled" -> RuntimeConfigService.isSecretCopyBlockEnabled()
    "secretExternalLinkBlockEnabled" -> RuntimeConfigService.isSecretExternalLinkBlockEnabled()
    "secretForwardBlockEnabled" -> RuntimeConfigService.isSecretForwardBlockEnabled()
    "secretLinkPreviewBlockEnabled" -> RuntimeConfigService.isSecretLinkPreviewBlockEnabled()
    "secretListPreviewBlockEnabled" -> RuntimeConfigService.isSecretListPreviewBlockEnabled()
    "secretMediaExportBlockEnabled" -> RuntimeConfigService.isSecretMediaExportBlockEnabled()
    "secretNotifPreviewBlockEnabled" -> RuntimeConfigService.isSecretNotifPreviewBlockEnabled()
    "secretReactionBlockEnabled" -> RuntimeConfigService.isSecretReactionBlockEnabled()
    "secretStarBlockEnabled" -> RuntimeConfigService.isSecretStarBlockEnabled()
    "spoilerMediaEnabled" -> RuntimeConfigService.isSpoilerMediaEnabled()
    "staticLocationEnabled" -> RuntimeConfigService.isStaticLocationEnabled()
    "stickersEnabled" -> RuntimeConfigService.isStickersEnabled()
    "taskRemindersEnabled" -> RuntimeConfigService.isTaskRemindersEnabled()
    "typingIndicatorsEnabled" -> RuntimeConfigService.isTypingIndicatorsEnabled()
    "unreadPriorityEnabled" -> RuntimeConfigService.isUnreadPriorityEnabled()
    "videoCallEnabled" -> RuntimeConfigService.isVideoCallEnabled()
    "videoSendEnabled" -> RuntimeConfigService.isVideoSendEnabled()
    "viewOnceEnabled" -> RuntimeConfigService.isViewOnceEnabled()
    "voiceCallEnabled" -> RuntimeConfigService.isVoiceCallEnabled()
    else -> error("Unknown bot runtime flag: $name")
}
