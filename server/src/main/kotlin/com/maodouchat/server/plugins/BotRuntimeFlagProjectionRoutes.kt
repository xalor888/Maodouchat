package com.maodouchat.server.plugins

import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isAiEnabled
import com.maodouchat.server.service.isAppLockEnabled
import com.maodouchat.server.service.isAutoDownloadEnabled
import com.maodouchat.server.service.isBlindWatermarkEnabled
import com.maodouchat.server.service.isBlockReportEnabled
import com.maodouchat.server.service.isCaptureAlertEnabled
import com.maodouchat.server.service.isChatAnimationsEnabled
import com.maodouchat.server.service.isChatArchiveEnabled
import com.maodouchat.server.service.isChatDraftsEnabled
import com.maodouchat.server.service.isChatFontScaleEnabled
import com.maodouchat.server.service.isChatLockEnabled
import com.maodouchat.server.service.isChatMuteEnabled
import com.maodouchat.server.service.isChatPinEnabled
import com.maodouchat.server.service.isChatWallpaperEnabled
import com.maodouchat.server.service.isContactCardEnabled
import com.maodouchat.server.service.isDisappearingMessagesEnabled
import com.maodouchat.server.service.isDndEnabled
import com.maodouchat.server.service.isFileShareEnabled
import com.maodouchat.server.service.isFriendRequestsEnabled
import com.maodouchat.server.service.isGifSendEnabled
import com.maodouchat.server.service.isGroupInvitesEnabled
import com.maodouchat.server.service.isGroupPlayEnabled
import com.maodouchat.server.service.isHapticsEnabled
import com.maodouchat.server.service.isImageSendEnabled
import com.maodouchat.server.service.isInAppSoundsEnabled
import com.maodouchat.server.service.isLiveLocationEnabled
import com.maodouchat.server.service.isMarkdownEnabled
import com.maodouchat.server.service.isMarkedUnreadEnabled
import com.maodouchat.server.service.isMediaUploadEnabled
import com.maodouchat.server.service.isMentionsEnabled
import com.maodouchat.server.service.isMessageEditEnabled
import com.maodouchat.server.service.isMessageForwardingEnabled
import com.maodouchat.server.service.isMessagePinEnabled
import com.maodouchat.server.service.isMessageRevokeEnabled
import com.maodouchat.server.service.isMessageStarringEnabled
import com.maodouchat.server.service.isNavTransitionsEnabled
import com.maodouchat.server.service.isNearbyEnabled
import com.maodouchat.server.service.isNotificationPreviewEnabled
import com.maodouchat.server.service.isNotificationSoundEnabled
import com.maodouchat.server.service.isNudgeEnabled
import com.maodouchat.server.service.isPollsEnabled
import com.maodouchat.server.service.isPostsEnabled
import com.maodouchat.server.service.isPushNotificationsEnabled
import com.maodouchat.server.service.isQrCodeEnabled
import com.maodouchat.server.service.isReactionsEnabled
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
import com.maodouchat.server.service.isStickersEnabled
import com.maodouchat.server.service.isTaskRemindersEnabled
import com.maodouchat.server.service.isTypingIndicatorsEnabled
import com.maodouchat.server.service.isUnreadPriorityEnabled
import com.maodouchat.server.service.isVideoCallEnabled
import com.maodouchat.server.service.isVideoSendEnabled
import com.maodouchat.server.service.isViewOnceEnabled
import com.maodouchat.server.service.isVoiceCallEnabled
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
