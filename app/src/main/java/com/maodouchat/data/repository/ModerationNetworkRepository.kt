package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.RiskEventResponse
import com.maodouchat.network.UpdateModerationRuleRequest

/**
 * 举报 / 审核 / 风控的**远端**调用（G328c）。
 *
 * 同一组端点在两处被用到：用户侧（我的举报、拉黑）与审核员侧（举报处置、规则、风险事件）。
 * 它们共享同一批失败语义（权限不足、条目已处置、风控事件过期），所以放在一个仓库里，
 * 而不是拆成「用户侧/审核侧」两个——那样两边的错误处理会各写一遍。
 *
 * 刻意很薄（不缓存、不重试）：处置动作必须看到服务端的最新状态，
 * 缓存它会让审核员对着过期列表做决定。
 */
internal class ModerationNetworkRepository(
    private val myReportsApi: suspend (String, Int) -> Result<List<ReportResponse>> =
        { token, limit -> ApiService.getMyReports(token, limit) },
    private val adminReportsApi: suspend (String, String?, Int) -> Result<List<ReportResponse>> =
        { token, status, limit -> ApiService.getAdminReports(token, status, limit) },
    private val updateReportStatusApi: suspend (String, String, String, String?) -> Result<ReportResponse> =
        { token, reportId, status, note -> ApiService.updateReportStatus(token, reportId, status, note) },
    private val applyReportActionApi: suspend (String, String, String, String?) -> Result<ReportResponse> =
        { token, reportId, action, note -> ApiService.applyReportAction(token, reportId, action, note) },
    private val rulesApi: suspend (String) -> Result<List<ModerationRuleResponse>> =
        { token -> ApiService.getModerationRules(token) },
    private val updateRuleApi: suspend (String, String, UpdateModerationRuleRequest) -> Result<ModerationRuleResponse> =
        { token, ruleId, request -> ApiService.updateModerationRule(token, ruleId, request) },
    private val riskEventsApi: suspend (String, Boolean?, Int) -> Result<List<RiskEventResponse>> =
        { token, needsReview, limit -> ApiService.getRiskEvents(token, needsReview, limit) },
    private val acknowledgeRiskApi: suspend (String, String) -> Result<Unit> =
        { token, eventId -> ApiService.acknowledgeRiskEvent(token, eventId) },
    private val blockUserApi: suspend (String, String) -> Result<Unit> =
        { token, userId -> ApiService.blockUser(token, userId) },
    private val blockedIdsApi: suspend (String) -> Result<List<String>> =
        { token -> ApiService.getBlockedUsers(token) },
    private val createReportApi: suspend (String, String, String, String?, String?, String, String?) -> Result<ReportResponse> =
        { token, targetType, targetId, chatId, messageId, reason, description ->
            ApiService.createReport(token, targetType, targetId, chatId, messageId, reason, description)
        },
) {
    suspend fun myReports(token: String? = null, limit: Int = 50): Result<List<ReportResponse>> =
        myReportsApi(token ?: currentAccessToken(), limit)

    suspend fun adminReports(token: String? = null, status: String? = null, limit: Int = 100): Result<List<ReportResponse>> =
        adminReportsApi(token ?: currentAccessToken(), status, limit)

    suspend fun updateReportStatus(
        token: String? = null,
        reportId: String,
        status: String,
        resolutionNote: String? = null,
    ): Result<ReportResponse> = updateReportStatusApi(token ?: currentAccessToken(), reportId, status, resolutionNote)

    suspend fun applyReportAction(
        token: String? = null,
        reportId: String,
        action: String,
        resolutionNote: String? = null,
    ): Result<ReportResponse> = applyReportActionApi(token ?: currentAccessToken(), reportId, action, resolutionNote)

    suspend fun moderationRules(token: String? = null): Result<List<ModerationRuleResponse>> =
        rulesApi(token ?: currentAccessToken())

    suspend fun updateModerationRule(
        token: String? = null,
        ruleId: String,
        request: UpdateModerationRuleRequest,
    ): Result<ModerationRuleResponse> = updateRuleApi(token ?: currentAccessToken(), ruleId, request)

    suspend fun riskEvents(token: String? = null, needsReview: Boolean? = null, limit: Int = 100): Result<List<RiskEventResponse>> =
        riskEventsApi(token ?: currentAccessToken(), needsReview, limit)

    suspend fun acknowledgeRiskEvent(token: String? = null, eventId: String): Result<Unit> =
        acknowledgeRiskApi(token ?: currentAccessToken(), eventId)

    suspend fun blockUser(token: String? = null, userId: String): Result<Unit> = blockUserApi(token ?: currentAccessToken(), userId)

    /** 已拉黑的**用户 id 列表**（与 `blockedUserDetails` 的区别：那个返回用户资料）。 */
    suspend fun blockedUserIds(token: String? = null): Result<List<String>> =
        blockedIdsApi(token ?: currentAccessToken())

    /**
     * 提交举报。`targetType` 决定后半组的必填项：`USER` 看 `targetId`，
     * `MESSAGE` 还要 `messageId`——两者都是可空 String，传错位置编译不报错，
     * 所以这里把服务端要求的**七个位置参数**一个不改地透传，不做任何「智能推断」。
     */
    suspend fun createReport(
        token: String? = null,
        targetType: String,
        targetId: String,
        chatId: String? = null,
        messageId: String? = null,
        reason: String,
        description: String? = null,
    ): Result<ReportResponse> =
        createReportApi(token ?: currentAccessToken(), targetType, targetId, chatId, messageId, reason, description)
}
