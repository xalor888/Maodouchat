package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot API 身份与信息探针（getMe / webhookInfo / chats）。 */
internal fun Route.configureBotInfoRoutes(
    botSendRateLimiter: BoundedRateLimiter,
    conversationParticipantRepo: ConversationParticipantRepository,
) {

    get("/api/bot/webhookInfo") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "webhookInfo")
        call.respond(
        buildJsonObject {
put("url", (bot.webhookUrl ?: ""))
put("hasCustomCertificate", false)
put("pendingUpdateCount", pending)
put("signed", true)
put("signatureHeader", "X-Maodouchat-Signature")
put("maxConnections", 40)
        }
    )
    }

    get("/api/bot/me") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        call.respond(
            buildJsonObject {
                put("id", bot.id)
                put("name", bot.name)
                put("username", bot.username)
                put("description", (bot.description ?: ""))
                put("enabled", bot.enabled)
                put("webhookUrl", (bot.webhookUrl ?: ""))
                putJsonArray("commands") {
                    commands.forEach { c -> add(buildJsonObject { put("command", c.command); put("description", c.description) }) }
                }
                put("canJoinGroups", true)
                put("supportsInline", false)
                put("canSendDocuments", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
                put("canSendVoice", (com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled() && com.maodouchat.server.service.RuntimeConfigService.isVoiceMessagesEnabled()))
                put("canReadGroupHistory", true)
                put("mediaUploadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
                put("groupPlayEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled())
                put("callsEnabled", com.maodouchat.server.service.RuntimeConfigService.isCallsEnabled())
                put("scheduledMessagesEnabled", com.maodouchat.server.service.RuntimeConfigService.isScheduledMessagesEnabled())
                put("viewOnceEnabled", com.maodouchat.server.service.RuntimeConfigService.isViewOnceEnabled())
                put("liveLocationEnabled", com.maodouchat.server.service.RuntimeConfigService.isLiveLocationEnabled())
                put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
                put("typingIndicatorsEnabled", com.maodouchat.server.service.RuntimeConfigService.isTypingIndicatorsEnabled())
                put("readReceiptsEnabled", com.maodouchat.server.service.RuntimeConfigService.isReadReceiptsEnabled())
                put("presenceEnabled", com.maodouchat.server.service.RuntimeConfigService.isPresenceEnabled())
                put("messageStarringEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled())
                put("chatExportEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatExportEnabled())
                put("messageForwardingEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageForwardingEnabled())
                put("globalSearchEnabled", com.maodouchat.server.service.RuntimeConfigService.isGlobalSearchEnabled())
                put("friendRequestsEnabled", com.maodouchat.server.service.RuntimeConfigService.isFriendRequestsEnabled())
                put("chatFoldersEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatFoldersEnabled())
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
                put("reactionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isReactionsEnabled())
                put("stickersEnabled", com.maodouchat.server.service.RuntimeConfigService.isStickersEnabled())
                put("silentSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled())
        }
    )
    }

    get("/api/bot/chats") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chats = conversationParticipantRepo.chatIdsForUser(bot.id)
        call.respond(
        buildJsonObject {
put("chatIds", Json.parseToJsonElement(Json.encodeToString(chats)))
put("count", chats.size)
        }
    )
    }
}
