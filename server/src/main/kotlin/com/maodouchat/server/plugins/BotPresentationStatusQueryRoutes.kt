package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
/** Bot 状态查询：公开状态、服务器时间、whoami、能力矩阵。 */
internal fun Route.configureBotPresentationStatusQueryRoutes(
    botRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getPublicStatus") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getPublicStatus")
        call.respond(
        buildJsonObject {
put("ok", true)
put("postsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPostsEnabled())
put("blockReportEnabled", com.maodouchat.server.service.RuntimeConfigService.isBlockReportEnabled())
put("chatArchiveEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatArchiveEnabled())
put("nearbyEnabled", com.maodouchat.server.service.RuntimeConfigService.isNearbyEnabled())
put("chatPinEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatPinEnabled())
put("markedUnreadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkedUnreadEnabled())
put("chatMuteEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatMuteEnabled())
put("disappearingMessagesEnabled", com.maodouchat.server.service.RuntimeConfigService.isDisappearingMessagesEnabled())
put("chatLockEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatLockEnabled())
put("messageEditEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageEditEnabled())
put("messagePinEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessagePinEnabled())
put("messageRevokeEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageRevokeEnabled())
put("pollsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPollsEnabled())
put("appLockEnabled", com.maodouchat.server.service.RuntimeConfigService.isAppLockEnabled())
put("chatDraftsEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatDraftsEnabled())
put("groupInvitesEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled())
put("mentionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isMentionsEnabled())
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
put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
put("friendRequestsEnabled", com.maodouchat.server.service.RuntimeConfigService.isFriendRequestsEnabled())
put("chatFoldersEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatFoldersEnabled())
put("serverTime", System.currentTimeMillis())
        }
    )
    }

    get("/api/bot/getServerTime") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getServerTime")
        call.respond(
        buildJsonObject {
put("ok", true)
put("serverTime", System.currentTimeMillis())
        }
    )
    }

    get("/api/bot/whoami") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "whoami")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("name", bot.name)
put("username", bot.username)
put("enabled", bot.enabled)
        }
    )
    }

    get("/api/bot/getFeatureMatrix") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getFeatureMatrix")
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatPinEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatPinEnabled())
put("markedUnreadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkedUnreadEnabled())
put("chatMuteEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatMuteEnabled())
put("disappearingMessagesEnabled", com.maodouchat.server.service.RuntimeConfigService.isDisappearingMessagesEnabled())
put("chatLockEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatLockEnabled())
put("messageEditEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageEditEnabled())
put("messagePinEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessagePinEnabled())
put("messageRevokeEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageRevokeEnabled())
put("pollsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPollsEnabled())
put("appLockEnabled", com.maodouchat.server.service.RuntimeConfigService.isAppLockEnabled())
put("chatDraftsEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatDraftsEnabled())
put("groupInvitesEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled())
put("mentionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isMentionsEnabled())
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
put("chatArchiveEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatArchiveEnabled())
put("nearbyEnabled", com.maodouchat.server.service.RuntimeConfigService.isNearbyEnabled())
put("postsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPostsEnabled())
put("blockReportEnabled", com.maodouchat.server.service.RuntimeConfigService.isBlockReportEnabled())
put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
put("groupPlayEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled())
put("serverTime", System.currentTimeMillis())
        }
    )
    }

}
