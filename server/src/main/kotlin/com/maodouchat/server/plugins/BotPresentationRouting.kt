package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/** Bot presentation, capability, and compatibility endpoints. */
internal fun Route.configureBotPresentationRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotPresentationWidgetsRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )

    configureBotPresentationStatusRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )

    configureBotPresentationCardsRoutes(
        userRepository = userRepository,
        participantRepository = participantRepository,
        serviceMessageRepository = serviceMessageRepository,
        botRateLimiter = botRateLimiter,
        json = json,
    )

    get("/api/bot/shieldz") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "shieldz")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
// 9.133：与同面 getCaptureShieldFlags 对齐（此前 ping 59 / flags 60 漂移，客户端按 surface 取能力集会不一致）
put("surface", 60)
put("ping", "shield")
        }
    )
    }

    get("/api/bot/listCapabilities") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "listCapabilities")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("capabilities", Json.parseToJsonElement(Json.encodeToString(listOf(
                    "sendMessage", "sendMarkdown", "sendCode", "sendQuote", "sendChecklist", "sendTable",
                    "sendBadge", "sendProgress", "sendCountdown", "sendAlert", "sendRemind", "sendDivider", "sendToast", "sendKeyValue", "sendNotice", "sendQuoteCard", "sendBanner", "sendJsonCard", "sendTimeline", "sendMetric", "sendSteps", "sendCompare", "sendMentionCard", "sendInviteHint", "sendNudgeCard", "sendSafetyHint", "sendQrHint", "sendContactCard", "sendSpoilerHint", "sendDownloadHint", "sendLocationHint", "sendFileHint", "sendSecureHint", "sendPhotoHint", "sendVideoHint", "sendGifHint", "sendWatermarkHint", "sendVoiceCallHint", "sendVideoCallHint", "sendWallpaperHint", "sendFontScaleHint", "sendUnreadHint", "sendRingtoneHint", "sendSoundHint", "sendPreviewHint", "sendPushHint", "sendTaskReminderHint", "sendDndHint", "sendSoundscapeHint", "sendHapticsHint", "sendMotionHint", "sendNavHint", "sendCaptureDetectHint", "sendRecentsHint", "sendSecretCopyHint", "sendSecretExportHint", "sendSecretForwardHint", "sendSecretChatExportHint", "sendSealedSenderHint", "sendPqxdhHint", "sendSecretAutoDisappearHint", "sendSecretLinkPreviewHint", "sendSecretExternalLinkHint", "sendSecretNotifPreviewHint", "sendSecretListPreviewHint",
                    "sendPhoto", "sendDocument", "sendPoll", "sendDice", "setMessageReaction",
                    "pinChatMessage", "getUpdates", "webhook", "getRuntimeFlags", "whoami", "getServerTime", "getFeatureMatrix", "echo", "getMuteArchiveFlags", "getVersion", "getPrivacyFlags", "healthz", "getMessagePolicyFlags", "uptime", "getEngagementFlags", "ping", "getComposerFlags", "echoTime", "getSocialFlags", "versionz", "getTrustFlags", "readyz", "getIdentityFlags", "alivez", "getMediaFlags", "statusz", "getLocationFlags", "getPrivacySecureFlags", "heartbeatz", "getMediaSendFlags", "pulsez", "tickz", "tockz", "clangz", "getMediaPrivacyFlags", "dingz", "getCallMediaFlags", "buzzz", "getAppearanceFlags", "chimez", "getNotifyFlags", "ringz", "getAlertMediaFlags", "beepz", "getPushFlags", "pushz", "getQuietFlags", "quietz", "getFeelFlags", "fealz", "getMotionFlags", "slidez", "getCaptureShieldFlags", "shieldz", "getSecretLeakFlags", "leakz", "getSecretVaultFlags", "vaultz", "getSealedCryptoFlags", "sealz", "getMarkPrivacyFlags", "markz", "getLinkPrivacyFlags", "linkz", "getNotifyPrivacyFlags", "privz", "sendSecretReactionHint", "sendSecretStarHint", "getSecretMetaFlags", "metaz", "sendSecretTypingHint", "sendSecretReadReceiptHint", "getSecretTypingFlags", "getSecretReadReceiptFlags", "typtz", "redz", "sendSecretPresenceHint", "sendSecretLastSeenHint", "getSecretPresenceFlags", "getSecretLastSeenFlags", "presz", "lastsz",
                    "burnz", "ttlz", "fwlz", "simz", "2faz", "ndz", "dvz", "sntz", "getSecretSurfaceFlags",
                    "sendSecretScreenshotBurnHint", "sendSecretAutoDestroyHint", "sendSecretForwardWhitelistHint", "sendSecretSimChangeHint", "sendSecret2faGateHint", "sendSecretNewDeviceRiskHint", "sendSecretDeviceVerifyHint", "sendSecretSessionNoticeHint"
                ))))
put("runtime", buildJsonObject {
put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
put("mediaUploadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
put("groupPlayEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled())
put("reactionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isReactionsEnabled())
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
put("secretTypingBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretTypingBlockEnabled())
put("secretReadReceiptBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretReadReceiptBlockEnabled())
put("secretPresenceBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretPresenceBlockEnabled())
put("secretLastSeenBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretLastSeenBlockEnabled())
})
        }
    )
    }
}
