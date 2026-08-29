package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.CacheService
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.WebRtcBinaryService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Public update, runtime status, and WebRTC binary delivery endpoints. */
internal fun Route.configurePublicUpdateRoutes(cacheService: CacheService) {
    get("/api/public/updates") {
        val versionCode = RuntimeConfigService.getInt(RuntimeConfigService.KEY_UPDATE_VERSION_CODE, 0)
        val versionName = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_VERSION_NAME).ifBlank { "0" }
        val apkUrl = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_APK_URL)
        val apkSha256 = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_APK_SHA256)
        val serverUrl = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_SERVER_URL).ifBlank { ServerConfig.baseUrl }
        val notes = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_NOTES)
        call.respond(buildJsonObject {
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkUrl", apkUrl)
            put("apkSha256", apkSha256)
            put("serverUrl", serverUrl)
            put("notes", notes)
        })
    }

    get("/api/public/app-update/latest.apk") {
        val file = com.maodouchat.server.update.AppUpdateStorage.latestFile()
        if (!file.isFile || file.length() < com.maodouchat.server.update.AppUpdatePublishPolicy.MIN_APK_BYTES) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("更新包尚未发布"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat.apk\"")
        call.respondFile(file)
    }

    put("/api/internal/app-update") {
        val expected = ServerConfig.updateDeployToken
        if (!com.maodouchat.server.update.AppUpdatePublishPolicy.tokenConfigured(expected)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("未配置更新发布"))
            return@put
        }
        val provided = com.maodouchat.server.update.AppUpdatePublishPolicy.bearerToken(
            call.request.header(HttpHeaders.Authorization)
        ).orEmpty()
        if (!com.maodouchat.server.update.AppUpdatePublishPolicy.tokensMatch(expected, provided)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("发布凭证无效"))
            return@put
        }
        val versionCode = com.maodouchat.server.update.AppUpdatePublishPolicy.parseVersionCode(call.request.header("X-Version-Code"))
        val versionName = com.maodouchat.server.update.AppUpdatePublishPolicy.parseVersionName(call.request.header("X-Version-Name"))
        if (versionCode == null || versionName == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("版本号无效"))
            return@put
        }
        val notes = com.maodouchat.server.update.AppUpdatePublishPolicy.sanitizeNotes(call.request.header("X-Update-Notes"))
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                com.maodouchat.server.update.AppUpdateStorage.saveFromStream(call.receiveStream())
            }
        }.getOrElse { error ->
            call.application.environment.log.warn("app-update write failed: ${error.message}", error)
            val message = when (error.message) {
                "too_large" -> "APK 过大"
                "too_small", "not_apk" -> "不是有效的 APK"
                else -> "写入更新包失败"
            }
            val status = if (error.message == "too_large") HttpStatusCode(413, "Payload Too Large") else HttpStatusCode.BadRequest
            call.respond(status, ErrorResponse(message))
            return@put
        }
        val apkUrl = com.maodouchat.server.update.AppUpdatePublishPolicy.publicApkUrl(ServerConfig.baseUrl)
        val apkSha256 = com.maodouchat.server.update.AppUpdateStorage.latestSha256()
            ?: error("update APK checksum unavailable")
        RuntimeConfigService.applyPublishedUpdate(versionCode, versionName, apkUrl, apkSha256, notes)
        call.respond(buildJsonObject {
            put("ok", true)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkUrl", apkUrl)
            put("apkSha256", apkSha256)
            put("bytes", saved.length())
        })
    }

    get("/api/public/status") {
        val statusCacheKey = "public_status"
        cacheService.getPublicStatus(statusCacheKey)?.let { cached ->
            call.respondText(cached as String, ContentType.Application.Json)
            return@get
        }
        val maintenance = RuntimeConfigService.isMaintenanceMode()
        val registrationOpen = RuntimeConfigService.isRegistrationAllowed()
        val body = buildJsonObject {
            put("ok", !maintenance)
            put("maintenance", maintenance)
            put("maintenanceMessage", if (maintenance) RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE) else "")
            put("registrationOpen", registrationOpen)
            put("inviteOnlyHint", if (registrationOpen) "" else RuntimeConfigService.get(RuntimeConfigService.KEY_INVITE_ONLY_HINT))
            put("globalBanner", RuntimeConfigService.get(RuntimeConfigService.KEY_GLOBAL_BANNER))
            put("forceE2eeBanner", RuntimeConfigService.get(RuntimeConfigService.KEY_FORCE_E2EE_BANNER))
            put("sealedSenderEnabled", RuntimeConfigService.isSealedSenderEnabled())
            put("allowBots", RuntimeConfigService.isBotsAllowed())
            put("maxGroupSize", RuntimeConfigService.getInt(RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200))
            put("maxMessagePerMin", RuntimeConfigService.maxMessagePerMinute())
            put("aiEnabled", RuntimeConfigService.isAiEnabled())
            put("publicAnnouncement", RuntimeConfigService.get(RuntimeConfigService.KEY_PUBLIC_ANNOUNCEMENT))
            put("pqxdhPreview", RuntimeConfigService.isPqxdhPreviewEnabled())
            put("minAppVersion", RuntimeConfigService.minAppVersion())
            put("captureAlertEnabled", RuntimeConfigService.isCaptureAlertEnabled())
            put("maxBotsPerUser", RuntimeConfigService.maxBotsPerUser())
            put("mediaUploadEnabled", RuntimeConfigService.isMediaUploadEnabled())
            put("groupPlayEnabled", RuntimeConfigService.isGroupPlayEnabled())
            put("linkPreviewEnabled", RuntimeConfigService.isLinkPreviewEnabled())
            put("voiceMessagesEnabled", RuntimeConfigService.isVoiceMessagesEnabled())
            put("reactionsEnabled", RuntimeConfigService.isReactionsEnabled())
            put("stickersEnabled", RuntimeConfigService.isStickersEnabled())
            put("silentSendEnabled", RuntimeConfigService.isSilentSendEnabled())
            put("callsEnabled", RuntimeConfigService.isCallsEnabled())
            put("scheduledMessagesEnabled", RuntimeConfigService.isScheduledMessagesEnabled())
            put("viewOnceEnabled", RuntimeConfigService.isViewOnceEnabled())
            put("liveLocationEnabled", RuntimeConfigService.isLiveLocationEnabled())
            put("markdownEnabled", RuntimeConfigService.isMarkdownEnabled())
            put("typingIndicatorsEnabled", RuntimeConfigService.isTypingIndicatorsEnabled())
            put("readReceiptsEnabled", RuntimeConfigService.isReadReceiptsEnabled())
            put("presenceEnabled", RuntimeConfigService.isPresenceEnabled())
            put("messageStarringEnabled", RuntimeConfigService.isMessageStarringEnabled())
            put("chatExportEnabled", RuntimeConfigService.isChatExportEnabled())
            put("messageForwardingEnabled", RuntimeConfigService.isMessageForwardingEnabled())
            put("globalSearchEnabled", RuntimeConfigService.isGlobalSearchEnabled())
            put("friendRequestsEnabled", RuntimeConfigService.isFriendRequestsEnabled())
            put("chatFoldersEnabled", RuntimeConfigService.isChatFoldersEnabled())
            put("postsEnabled", RuntimeConfigService.isPostsEnabled())
            put("blockReportEnabled", RuntimeConfigService.isBlockReportEnabled())
            put("chatArchiveEnabled", RuntimeConfigService.isChatArchiveEnabled())
            put("nearbyEnabled", RuntimeConfigService.isNearbyEnabled())
            put("chatPinEnabled", RuntimeConfigService.isChatPinEnabled())
            put("markedUnreadEnabled", RuntimeConfigService.isMarkedUnreadEnabled())
            put("chatMuteEnabled", RuntimeConfigService.isChatMuteEnabled())
            put("disappearingMessagesEnabled", RuntimeConfigService.isDisappearingMessagesEnabled())
            put("chatLockEnabled", RuntimeConfigService.isChatLockEnabled())
            put("messageEditEnabled", RuntimeConfigService.isMessageEditEnabled())
            put("messagePinEnabled", RuntimeConfigService.isMessagePinEnabled())
            put("messageRevokeEnabled", RuntimeConfigService.isMessageRevokeEnabled())
            put("pollsEnabled", RuntimeConfigService.isPollsEnabled())
            put("appLockEnabled", RuntimeConfigService.isAppLockEnabled())
            put("chatDraftsEnabled", RuntimeConfigService.isChatDraftsEnabled())
            put("groupInvitesEnabled", RuntimeConfigService.isGroupInvitesEnabled())
            put("mentionsEnabled", RuntimeConfigService.isMentionsEnabled())
            put("nudgeEnabled", RuntimeConfigService.isNudgeEnabled())
            put("safetyCodeEnabled", RuntimeConfigService.isSafetyCodeEnabled())
            put("qrCodeEnabled", RuntimeConfigService.isQrCodeEnabled())
            put("contactCardEnabled", RuntimeConfigService.isContactCardEnabled())
            put("spoilerMediaEnabled", RuntimeConfigService.isSpoilerMediaEnabled())
            put("autoDownloadEnabled", RuntimeConfigService.isAutoDownloadEnabled())
            put("staticLocationEnabled", RuntimeConfigService.isStaticLocationEnabled())
            put("fileShareEnabled", RuntimeConfigService.isFileShareEnabled())
            put("secretChatEnabled", RuntimeConfigService.isSecretChatEnabled())
            put("screenSecureRuntimeEnabled", RuntimeConfigService.isScreenSecureRuntimeEnabled())
            put("imageSendEnabled", RuntimeConfigService.isImageSendEnabled())
            put("videoSendEnabled", RuntimeConfigService.isVideoSendEnabled())
            put("gifSendEnabled", RuntimeConfigService.isGifSendEnabled())
            put("blindWatermarkEnabled", RuntimeConfigService.isBlindWatermarkEnabled())
            put("voiceCallEnabled", RuntimeConfigService.isVoiceCallEnabled())
            put("videoCallEnabled", RuntimeConfigService.isVideoCallEnabled())
            put("chatWallpaperEnabled", RuntimeConfigService.isChatWallpaperEnabled())
            put("chatFontScaleEnabled", RuntimeConfigService.isChatFontScaleEnabled())
            put("unreadPriorityEnabled", RuntimeConfigService.isUnreadPriorityEnabled())
            put("ringtoneEnabled", RuntimeConfigService.isRingtoneEnabled())
            put("notificationSoundEnabled", RuntimeConfigService.isNotificationSoundEnabled())
            put("notificationPreviewEnabled", RuntimeConfigService.isNotificationPreviewEnabled())
            put("pushNotificationsEnabled", RuntimeConfigService.isPushNotificationsEnabled())
            put("taskRemindersEnabled", RuntimeConfigService.isTaskRemindersEnabled())
            put("dndEnabled", RuntimeConfigService.isDndEnabled())
            put("inAppSoundsEnabled", RuntimeConfigService.isInAppSoundsEnabled())
            put("hapticsEnabled", RuntimeConfigService.isHapticsEnabled())
            put("chatAnimationsEnabled", RuntimeConfigService.isChatAnimationsEnabled())
            put("navTransitionsEnabled", RuntimeConfigService.isNavTransitionsEnabled())
            put("screenshotDetectEnabled", RuntimeConfigService.isScreenshotDetectEnabled())
            put("recentsExclusionEnabled", RuntimeConfigService.isRecentsExclusionEnabled())
            put("secretCopyBlockEnabled", RuntimeConfigService.isSecretCopyBlockEnabled())
            put("secretMediaExportBlockEnabled", RuntimeConfigService.isSecretMediaExportBlockEnabled())
            put("secretForwardBlockEnabled", RuntimeConfigService.isSecretForwardBlockEnabled())
            put("secretChatExportBlockEnabled", RuntimeConfigService.isSecretChatExportBlockEnabled())
            put("secretAutoDisappearEnabled", RuntimeConfigService.isSecretAutoDisappearEnabled())
            put("secretLinkPreviewBlockEnabled", RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
            put("secretExternalLinkBlockEnabled", RuntimeConfigService.isSecretExternalLinkBlockEnabled())
            put("secretNotifPreviewBlockEnabled", RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
            put("secretListPreviewBlockEnabled", RuntimeConfigService.isSecretListPreviewBlockEnabled())
            put("secretReactionBlockEnabled", RuntimeConfigService.isSecretReactionBlockEnabled())
            put("secretStarBlockEnabled", RuntimeConfigService.isSecretStarBlockEnabled())
            put("secretTypingBlockEnabled", RuntimeConfigService.isSecretTypingBlockEnabled())
            put("secretReadReceiptBlockEnabled", RuntimeConfigService.isSecretReadReceiptBlockEnabled())
            put("secretPresenceBlockEnabled", RuntimeConfigService.isSecretPresenceBlockEnabled())
            put("secretLastSeenBlockEnabled", RuntimeConfigService.isSecretLastSeenBlockEnabled())
            put("secretSurfaceFlags", Json.parseToJsonElement(Json.encodeToString(publicSecretSurfaceFlags())))
            put("serverTime", System.currentTimeMillis())
        }.toString()
        cacheService.putPublicStatus(statusCacheKey, body)
        call.respondText(body, ContentType.Application.Json)
    }

    get("/api/webrtc/lib/{abi}") {
        val abi = call.parameters["abi"].orEmpty()
        if (!WebRtcBinaryService.isSupported(abi)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("不支持的 CPU 架构: $abi"))
            return@get
        }
        val file = WebRtcBinaryService.resolveFile(abi)
        if (file == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("WebRTC 原生库暂不可用"))
            return@get
        }
        val etag = WebRtcBinaryService.sha256(file, abi)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=2592000, immutable")
        call.response.header(HttpHeaders.ETag, "\"$etag\"")
        call.response.header("X-Content-SHA256", etag)
        call.response.header(HttpHeaders.ContentEncoding, "identity")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == "\"$etag\"") {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondFile(file)
    }
}
