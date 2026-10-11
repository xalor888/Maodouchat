package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** B14：水印提取执行时间上限（毫秒），超时返回 504。 */
private const val WATERMARK_EXTRACT_TIMEOUT_MS = 30_000L

/** 管理后台运维域：盲水印提取（从 AdminExportsOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminExportsWatermarkRoutes() {
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
}
