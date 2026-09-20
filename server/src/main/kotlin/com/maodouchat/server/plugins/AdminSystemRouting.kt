package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 管理后台「系统安全快照 + 运营配置」子域路由。
 * /security-snapshot 只读汇总当前运行时开关与风控计数；/settings 读写运营开关。
 */
internal fun Route.configureAdminSystemRoutes() {
    val adminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository()

    // ─── Blind watermark forensics ──────────────




    get("/security-snapshot") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val now = System.currentTimeMillis()
        val sealedOn = RuntimeConfigService.isSealedSenderEnabled()
        val botsOn = RuntimeConfigService.isBotsAllowed()
        val aiOn = RuntimeConfigService.isAiEnabled()
        val maint = RuntimeConfigService.isMaintenanceMode()
        val regOpen = RuntimeConfigService.isRegistrationAllowed()
        val ipBlocks = RuntimeConfigService.ipBlocklist().size
        val msgRate = RuntimeConfigService.maxMessagePerMinute()
        val online = try {
            com.maodouchat.server.plugins.ConnectionRegistry.onlineUserIds().size
        } catch (_: Exception) {
            0
        }
        val (users, riskOpen) = adminManagementRepository.systemOverviewStats()
        call.respond(
        buildJsonObject {
put("generatedAt", now)
put("ConnectionRegistry.onlineUsers", online)
put("usersTotal", users)
put("openRiskEvents", riskOpen)
put("flags", buildJsonObject {
put("sealedSenderEnabled", sealedOn)
put("botsAllowed", botsOn)
put("aiEnabled", aiOn)
put("maintenanceMode", maint)
put("registrationOpen", regOpen)
put("pqxdhPreview", RuntimeConfigService.isPqxdhPreviewEnabled())
put("captureAlertEnabled", RuntimeConfigService.isCaptureAlertEnabled())
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
})
put("limits", buildJsonObject {
put("maxMessagePerMin", msgRate)
put("ipBlocklistCount", ipBlocks)
put("maxGroupSize", RuntimeConfigService.getInt(RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200))
put("minAppVersion", RuntimeConfigService.minAppVersion())
put("maxBotsPerUser", RuntimeConfigService.maxBotsPerUser())
})
put("banner", RuntimeConfigService.get(RuntimeConfigService.KEY_GLOBAL_BANNER))
put("forceE2eeBanner", RuntimeConfigService.get(RuntimeConfigService.KEY_FORCE_E2EE_BANNER))
put("publicAnnouncement", RuntimeConfigService.get(RuntimeConfigService.KEY_PUBLIC_ANNOUNCEMENT))
        }
    )
    }

get("/settings") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        call.respond(
        buildJsonObject {
put("settings", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.all())))
put("defaults", Json.parseToJsonElement(Json.encodeToString(RuntimeConfigService.defaults())))
put("envAllowRegistration", ServerConfig.allowRegistration)
        }
    )
    }

    put("/settings") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val settingsObj = obj["settings"]?.jsonObject ?: obj
        val updates = settingsObj.mapNotNull { (k, v) ->
            val s = runCatching { v.jsonPrimitive.content }.getOrNull() ?: return@mapNotNull null
            k to s
        }.toMap()
        if (updates.isEmpty()) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("no settings"))
        }
        val applied = RuntimeConfigService.setMany(updates, actorId)
        recordAdminAudit(actorId, "ADMIN_SETTINGS_UPDATE", "keys=${updates.keys.joinToString(",")}")
        call.respond(
        buildJsonObject {
put("status", "ok")
put("settings", Json.parseToJsonElement(Json.encodeToString(applied)))
        }
    )
    }
}
