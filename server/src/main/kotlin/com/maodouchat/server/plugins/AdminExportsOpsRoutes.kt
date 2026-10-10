package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.csvCell
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 管理后台运维域导出/水印路由（从 AdminExportsRouting.kt 按域拆出；/ai-usage-export 从 AdminBulkRouting.kt 按域迁入）。 */

/** B14：水印提取执行时间上限（毫秒），超时返回 504。 */
private const val WATERMARK_EXTRACT_TIMEOUT_MS = 30_000L

internal fun Route.configureAdminExportsOpsRoutes() {
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
post("/watermark/extract") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_WATERMARK_BODY_CHARS) }.getOrNull().orEmpty()
        if (bodyText.length > MAX_ADMIN_WATERMARK_BODY_CHARS) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("请求体过大"))
        }
        val imageB64 = parseAdminWatermarkImageBase64(bodyText)
        if (imageB64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("imageBase64_required"))
        }
        // B14：水印提取为 CPU 密集（DWT+SVD / DCT-QIM），异步到 Default 线程池，
        // 并用超时兜底，避免阻塞 Ktor 事件循环、恶意大图拖垮请求线程。
        val result = withContext(Dispatchers.Default) {
            withTimeoutOrNull(WATERMARK_EXTRACT_TIMEOUT_MS) {
                com.maodouchat.server.watermark.AdminWatermarkExtractor.extractFromBase64(imageB64)
            }
        }
        if (result == null) {
            return@post call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse("水印提取超时，请重试"))
        }
        recordAdminAudit(
            actorId = adminId,
            action = "watermark_extract",
            detail = "found=${result.found};msg=${result.message};hex=${result.payloadHex.orEmpty().take(24)}"
        )
        call.respond(
            WatermarkExtractResponse(
                found = result.found,
                payloadHex = result.payloadHex.orEmpty(),
                width = result.width,
                height = result.height,
                message = result.message,
                notes = "payload is FNV-1a48 of userId|chatId|deviceHint; visible tiles also embed wall-clock time"
            )
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
