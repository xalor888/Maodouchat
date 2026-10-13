package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.csvCell
import com.maodouchat.server.service.ipBlocklist
import com.maodouchat.server.service.isAiContentModerationEnabled
import com.maodouchat.server.service.isAiEnabled
import com.maodouchat.server.service.isBlindWatermarkEnabled
import com.maodouchat.server.service.isBotsAllowed
import com.maodouchat.server.service.isCaptureAlertEnabled
import com.maodouchat.server.service.isChatAnimationsEnabled
import com.maodouchat.server.service.isChatFontScaleEnabled
import com.maodouchat.server.service.isChatWallpaperEnabled
import com.maodouchat.server.service.isDndEnabled
import com.maodouchat.server.service.isGifSendEnabled
import com.maodouchat.server.service.isHapticsEnabled
import com.maodouchat.server.service.isImageSendEnabled
import com.maodouchat.server.service.isInAppSoundsEnabled
import com.maodouchat.server.service.isNavTransitionsEnabled
import com.maodouchat.server.service.isNotificationPreviewEnabled
import com.maodouchat.server.service.isNotificationSoundEnabled
import com.maodouchat.server.service.isPqxdhPreviewEnabled
import com.maodouchat.server.service.isPushNotificationsEnabled
import com.maodouchat.server.service.isRecentsExclusionEnabled
import com.maodouchat.server.service.isRingtoneEnabled
import com.maodouchat.server.service.isScreenshotDetectEnabled
import com.maodouchat.server.service.isSealedSenderEnabled
import com.maodouchat.server.service.isSecretAutoDisappearEnabled
import com.maodouchat.server.service.isSecretChatExportBlockEnabled
import com.maodouchat.server.service.isSecretCopyBlockEnabled
import com.maodouchat.server.service.isSecretExternalLinkBlockEnabled
import com.maodouchat.server.service.isSecretForwardBlockEnabled
import com.maodouchat.server.service.isSecretLastSeenBlockEnabled
import com.maodouchat.server.service.isSecretLinkPreviewBlockEnabled
import com.maodouchat.server.service.isSecretListPreviewBlockEnabled
import com.maodouchat.server.service.isSecretMediaExportBlockEnabled
import com.maodouchat.server.service.isSecretNotifPreviewBlockEnabled
import com.maodouchat.server.service.isSecretPresenceBlockEnabled
import com.maodouchat.server.service.isSecretReactionBlockEnabled
import com.maodouchat.server.service.isSecretReadReceiptBlockEnabled
import com.maodouchat.server.service.isSecretStarBlockEnabled
import com.maodouchat.server.service.isSecretTypingBlockEnabled
import com.maodouchat.server.service.isTaskRemindersEnabled
import com.maodouchat.server.service.isUnreadPriorityEnabled
import com.maodouchat.server.service.isVideoCallEnabled
import com.maodouchat.server.service.isVideoSendEnabled
import com.maodouchat.server.service.isVoiceCallEnabled
import com.maodouchat.server.service.maxBotsPerUser
import com.maodouchat.server.service.maxMessagePerMinute
import com.maodouchat.server.service.minAppVersion
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maodouchat.server.repository.auditExportRows

/** 管理后台运维域：AI 特性开关 / 运行时配置 / AI 用量审计导出（从 AdminExportsOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminExportsDataRoutes() {
    val aiRepo = com.maodouchat.server.repository.AiRepository()
get("/ai-feature-flags-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val csv = buildString {
        appendLine("key,value")
        appendLine("ai_enabled," + RuntimeConfigService.isAiEnabled())
        appendLine("ai_content_moderation_enabled," + RuntimeConfigService.isAiContentModerationEnabled())
        appendLine("gif_send_enabled," + RuntimeConfigService.isGifSendEnabled())
        appendLine("blind_watermark_enabled," + RuntimeConfigService.isBlindWatermarkEnabled())
        appendLine("voice_call_enabled," + RuntimeConfigService.isVoiceCallEnabled())
        appendLine("video_call_enabled," + RuntimeConfigService.isVideoCallEnabled())
        appendLine("chat_wallpaper_enabled," + RuntimeConfigService.isChatWallpaperEnabled())
        appendLine("chat_font_scale_enabled," + RuntimeConfigService.isChatFontScaleEnabled())
        appendLine("unread_priority_enabled," + RuntimeConfigService.isUnreadPriorityEnabled())
        appendLine("ringtone_enabled," + RuntimeConfigService.isRingtoneEnabled())
        appendLine("notification_sound_enabled," + RuntimeConfigService.isNotificationSoundEnabled())
        appendLine("notification_preview_enabled," + RuntimeConfigService.isNotificationPreviewEnabled())
        appendLine("push_notifications_enabled," + RuntimeConfigService.isPushNotificationsEnabled())
        appendLine("task_reminders_enabled," + RuntimeConfigService.isTaskRemindersEnabled())
        appendLine("dnd_enabled," + RuntimeConfigService.isDndEnabled())
        appendLine("in_app_sounds_enabled," + RuntimeConfigService.isInAppSoundsEnabled())
        appendLine("haptics_enabled," + RuntimeConfigService.isHapticsEnabled())
        appendLine("chat_animations_enabled," + RuntimeConfigService.isChatAnimationsEnabled())
        appendLine("nav_transitions_enabled," + RuntimeConfigService.isNavTransitionsEnabled())
        appendLine("screenshot_detect_enabled," + RuntimeConfigService.isScreenshotDetectEnabled())
        appendLine("recents_exclusion_enabled," + RuntimeConfigService.isRecentsExclusionEnabled())
        appendLine("secret_copy_block_enabled," + RuntimeConfigService.isSecretCopyBlockEnabled())
        appendLine("secret_media_export_block_enabled," + RuntimeConfigService.isSecretMediaExportBlockEnabled())
        appendLine("secret_forward_block_enabled," + RuntimeConfigService.isSecretForwardBlockEnabled())
        appendLine("secret_chat_export_block_enabled," + RuntimeConfigService.isSecretChatExportBlockEnabled())
        appendLine("secret_auto_disappear_enabled," + RuntimeConfigService.isSecretAutoDisappearEnabled())
        appendLine("secret_link_preview_block_enabled," + RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
        appendLine("secret_external_link_block_enabled," + RuntimeConfigService.isSecretExternalLinkBlockEnabled())
        appendLine("secret_notif_preview_block_enabled," + RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
        appendLine("secret_list_preview_block_enabled," + RuntimeConfigService.isSecretListPreviewBlockEnabled())
        appendLine("secret_reaction_block_enabled," + RuntimeConfigService.isSecretReactionBlockEnabled())
        appendLine("secret_star_block_enabled," + RuntimeConfigService.isSecretStarBlockEnabled())
        appendLine("secret_typing_block_enabled," + RuntimeConfigService.isSecretTypingBlockEnabled())
        appendLine("secret_read_receipt_block_enabled," + RuntimeConfigService.isSecretReadReceiptBlockEnabled())
        appendLine("secret_presence_block_enabled," + RuntimeConfigService.isSecretPresenceBlockEnabled())
        appendLine("secret_last_seen_block_enabled," + RuntimeConfigService.isSecretLastSeenBlockEnabled())
        appendLine("image_send_enabled," + RuntimeConfigService.isImageSendEnabled())
        appendLine("video_send_enabled," + RuntimeConfigService.isVideoSendEnabled())
    }
    recordAdminAudit(actorId = adminId, action = "ai_feature_flags_export", detail = "runtime flags")
    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat-ai-feature-flags.csv\"")
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/runtime-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val actorId = call.requireUserId()
    recordAdminAudit(actorId, "ADMIN_RUNTIME_EXPORT", "settings snapshot")
    call.respond(
    buildJsonObject {
put("generatedAt", System.currentTimeMillis())
putJsonElement("settings", RuntimeConfigService.all())
putJsonElement("defaults", RuntimeConfigService.defaults())
put("security", buildJsonObject {
put("sealedSenderEnabled", RuntimeConfigService.isSealedSenderEnabled())
put("aiEnabled", RuntimeConfigService.isAiEnabled())
put("botsAllowed", RuntimeConfigService.isBotsAllowed())
put("captureAlertEnabled", RuntimeConfigService.isCaptureAlertEnabled())
put("pqxdhPreview", RuntimeConfigService.isPqxdhPreviewEnabled())
put("minAppVersion", RuntimeConfigService.minAppVersion())
put("maxBotsPerUser", RuntimeConfigService.maxBotsPerUser())
put("ipBlocklistCount", RuntimeConfigService.ipBlocklist().size)
put("maxMessagePerMin", RuntimeConfigService.maxMessagePerMinute())
})
    }
)
}

get("/ai-usage-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 2000, 10000)
    // Metadata only — never export prompt/body
    val rows = aiRepo.auditExportRows(limit).map { row ->
        listOf(
            csvCell(row.id),
            csvCell(row.userId),
            csvCell(row.feature.take(40)),
            csvCell(row.status),
            csvCell(row.inputChars.toString()),
            csvCell(row.contextMessages.toString()),
            csvCell((row.durationMs ?: 0L).toString()),
            csvCell((row.error ?: "").replace("\n", " ").take(80)),
            csvCell(row.createdAt.toString()),
            csvCell(row.inputTokens?.toString() ?: ""),
            csvCell(row.outputTokens?.toString() ?: "")
        ).joinToString(",")
    }
    val csv = buildString {
        appendLine("id,userId,feature,status,inputChars,contextMessages,durationMs,error,createdAt,inputTokens,outputTokens")
        rows.forEach { appendLine(it) }
    }
    recordAdminAudit(actorId = adminId, action = "ai_usage_export", detail = "count=${rows.size}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-ai-usage.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}
}
