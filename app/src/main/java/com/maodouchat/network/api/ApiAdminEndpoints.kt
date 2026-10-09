package com.maodouchat.network.api

import com.maodouchat.network.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.KSerializer

// 管理域（举报/审核/风控/公告）：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiAdminEndpoints {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)

// ─── 头像上传 + 修改资料 ─────────────────

suspend fun createReport(
    token: String,
    targetType: String,
    targetId: String,
    chatId: String?,
    messageId: String?,
    reason: String,
    description: String?): Result<ReportResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/reports")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(CreateReportRequest.serializer(), CreateReportRequest(targetType, targetId, chatId, messageId, reason, description))))
            .build(),
        ReportResponse.serializer()
    )

suspend fun getMyReports(token: String, limit: Int): Result<List<ReportResponse>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/reports/mine?limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ReportResponse.serializer()))

suspend fun getAdminReports(token: String, status: String?, limit: Int): Result<List<ReportResponse>> {
    val statusPart = status?.takeIf { it.isNotBlank() && it != "ALL" }?.let { "&status=$it" } ?: ""
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports?limit=${limit.coerceIn(1, 200)}$statusPart").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ReportResponse.serializer()))
}

suspend fun updateReportStatus(token: String, reportId: String, status: String, resolutionNote: String?): Result<ReportResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports/$reportId/status").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateReportStatusRequest.serializer(), UpdateReportStatusRequest(status, resolutionNote)))).build(), ReportResponse.serializer())

suspend fun applyReportAction(token: String, reportId: String, action: String, resolutionNote: String?): Result<ReportResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports/$reportId/action").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ApplyReportActionRequest.serializer(), ApplyReportActionRequest(action, resolutionNote)))).build(), ReportResponse.serializer())

suspend fun getModerationRules(token: String): Result<List<ModerationRuleResponse>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/rules").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ModerationRuleResponse.serializer()))

suspend fun updateModerationRule(token: String, ruleId: String, request: UpdateModerationRuleRequest): Result<ModerationRuleResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/rules/$ruleId").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateModerationRuleRequest.serializer(), request))).build(), ModerationRuleResponse.serializer())

suspend fun getRiskEvents(token: String, needsReview: Boolean?, limit: Int): Result<List<RiskEventResponse>> {
    val reviewPart = needsReview?.let { "&needsReview=$it" } ?: ""
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/events?limit=${limit.coerceIn(1, 200)}$reviewPart").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(RiskEventResponse.serializer()))
}

suspend fun acknowledgeRiskEvent(token: String, eventId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/events/$eventId/ack").addHeader("Authorization", "Bearer $token").post("".toRequestBody(JSON_MEDIA)).build())

/** 活跃公告（含本用户 acked 状态），返回 JSON 字符串由调用方解析。 */

suspend fun getActiveAnnouncements(token: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/announcements/active")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "announcements_active")
}

/** 公告已读确认。 */

/** 公告已读确认。 */

suspend fun ackAnnouncement(token: String, announcementId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/announcements/$announcementId/ack")
        .header("Authorization", "Bearer $token")
        .post(ByteArray(0).toRequestBody(null))
        .build()
    return executeForText(req, "announcement_ack")
}

/** 推送 HMAC 校验密钥（经认证通道下发；返回 JSON 字符串由调用方解析，key 为 null 表示未配置）。 */
}
