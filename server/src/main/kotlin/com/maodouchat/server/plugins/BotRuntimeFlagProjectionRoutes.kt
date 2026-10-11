package com.maodouchat.server.plugins

import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.routing.Route

/** Runtime-flag projections backed by one ordered catalog instead of copied response builders. */
internal fun Route.configureBotRuntimeFlagProjectionRoutes(botRateLimiter: BoundedRateLimiter) {
    configureBotRuntimeFlagProjectionCoreRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotRuntimeFlagProjectionChatRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotRuntimeFlagProjectionSocialRoutes(
        botRateLimiter = botRateLimiter,
    )
    configureBotRuntimeFlagProjectionSecureRoutes(
        botRateLimiter = botRateLimiter,
    )
}

internal data class BotRuntimeFlagProjection(
    val path: String,
    val flagNames: List<String>,
)

internal val BOT_CORE_FLAGS = listOf(
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

internal fun readBotRuntimeFlag(name: String): Boolean = when (name) {
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
