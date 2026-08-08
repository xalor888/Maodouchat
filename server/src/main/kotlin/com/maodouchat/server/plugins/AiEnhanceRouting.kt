package com.maodouchat.server.plugins

import com.maodouchat.server.model.AiContextMessage
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.service.AiEnhanceResult
import com.maodouchat.server.service.AiEnhanceService
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.AiStreamingService
import com.maodouchat.server.service.BudgetResult
import com.maodouchat.server.service.CrossChatQaCandidate
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * B4 · AI 增强能力路由（独立于巨型 Routing.kt，白名单校验 + 复用 AiGateway/AiRepository）。
 *
 * 红线：
 * - 服务端不落库任何会话明文，也不读取加密库；只透传客户端消毒后的消息做编排；
 * - 结果限白名单（跨聊天问答的引用来源 = 请求候选 ID 子集；消息分类类别受控枚举）；
 * - 走现有 Prompt 安全策略：AiGateway 各方法内部已声明「untrusted data / 不执行特权动作」。
 *
 * 端点均位于 authenticate("auth-jwt") 之下（与 Routing.kt 的 AI 端点一致）：
 * - POST /api/ai/enhance/conversation-profile
 * - POST /api/ai/enhance/weekly-report
 * - POST /api/ai/enhance/emotion-reply
 * - POST /api/ai/enhance/cross-chat-qa
 * - POST /api/ai/enhance/message-classes
 */
internal fun Application.configureAiEnhanceRouting(
    aiGateway: AiGateway,
    chatRepo: ChatRepository,
    aiRepo: AiRepository = AiRepository(),
    aiRateLimiter: BoundedRateLimiter = BoundedRateLimiter()
) {
    val service = AiEnhanceService(aiGateway)
    routing {
        authenticate("auth-jwt") {
            route("/api/ai/enhance") {
            post("/conversation-profile") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!RuntimeConfigService.isAiEnabled()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI temporarily disabled"))
                    return@post
                }
                if (!RuntimeConfigService.isAiSummaryEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("ai_summary_disabled"))
                    return@post
                }
                if (!aiRateLimiter.acquire(uid, maxPerMinute = 10)) {
                    aiRepo.recordAudit(uid, null, "conversation_profile", null, "rate_limited", 0, error = "rate_limited")
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 请求过于频繁"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseEnhance<ConversationProfilePayload>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (!isValidEnhanceContext(req.messages, maxMessages = MAX_ENHANCE_CONTEXT_MESSAGES, maxMessageChars = MAX_ENHANCE_MESSAGE_CHARS) ||
                    !isValidChatRef(req.chatId)
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("AI 上下文无效"))
                    return@post
                }
                if (!chatRepo.isParticipant(req.chatId, uid)) {
                    aiRepo.recordAudit(uid, req.chatId, "conversation_profile", null, "forbidden", inputChars(req.messages), error = "not_participant")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@post
                }
                if (!aiRepo.isEnabled(uid, req.chatId)) {
                    aiRepo.recordAudit(uid, req.chatId, "conversation_profile", null, "disabled", inputChars(req.messages))
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("AI 功能已关闭"))
                    return@post
                }
                val startedAt = System.currentTimeMillis()
                if (call.budgetExceeded(aiGateway, uid, req.messages, "conversation_profile", startedAt)) return@post
                when (val result = service.conversationProfile(req.messages, req.chatId)) {
                    is AiEnhanceResult.Success -> {
                        aiRepo.recordAudit(uid, req.chatId, "conversation_profile", result.value.model, "success", inputChars(req.messages), req.messages.size, durationMs = System.currentTimeMillis() - startedAt, inputTokens = result.inputTokens, outputTokens = result.outputTokens)
                        call.respond(ConversationProfileResponse(result.value.summary, result.value.model))
                    }
                    AiEnhanceResult.NotConfigured -> {
                        aiRepo.recordAudit(uid, req.chatId, "conversation_profile", null, "not_configured", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt)
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI 服务未配置"))
                    }
                    is AiEnhanceResult.Upstream -> call.respondUpstream(uid, req.chatId, "conversation_profile", result.error, req.messages, startedAt)
                    is AiEnhanceResult.Invalid -> {
                        aiRepo.recordAudit(uid, req.chatId, "conversation_profile", null, "invalid_response", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt, error = result.message)
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(result.message))
                    }
                }
            }

            post("/weekly-report") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!RuntimeConfigService.isAiEnabled()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI temporarily disabled"))
                    return@post
                }
                if (!RuntimeConfigService.isAiGroupAssistantEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("ai_group_assistant_disabled"))
                    return@post
                }
                if (!aiRateLimiter.acquire(uid, maxPerMinute = 6)) {
                    aiRepo.recordAudit(uid, null, "weekly_report", null, "rate_limited", 0, error = "rate_limited")
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 请求过于频繁"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseEnhance<WeeklyReportPayload>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (!isValidEnhanceContext(req.messages, maxMessages = MAX_ENHANCE_CONTEXT_MESSAGES, maxMessageChars = MAX_ENHANCE_MESSAGE_CHARS) ||
                    !isValidChatRef(req.chatId) ||
                    !isValidWeekRange(req.weekStart, req.weekEnd)
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("群周报参数无效"))
                    return@post
                }
                if (!chatRepo.isParticipant(req.chatId, uid)) {
                    aiRepo.recordAudit(uid, req.chatId, "weekly_report", null, "forbidden", inputChars(req.messages), error = "not_participant")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@post
                }
                if (!aiRepo.isEnabled(uid, req.chatId)) {
                    aiRepo.recordAudit(uid, req.chatId, "weekly_report", null, "disabled", inputChars(req.messages))
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("AI 功能已关闭"))
                    return@post
                }
                val startedAt = System.currentTimeMillis()
                if (call.budgetExceeded(aiGateway, uid, req.messages, "weekly_report", startedAt)) return@post
                when (val result = service.weeklyReport(req.messages, req.weekStart, req.weekEnd)) {
                    is AiEnhanceResult.Success -> {
                        aiRepo.recordAudit(uid, req.chatId, "weekly_report", result.value.model, "success", inputChars(req.messages), req.messages.size, durationMs = System.currentTimeMillis() - startedAt, inputTokens = result.inputTokens, outputTokens = result.outputTokens)
                        call.respond(WeeklyReportResponse(result.value.report, result.value.model))
                    }
                    AiEnhanceResult.NotConfigured -> {
                        aiRepo.recordAudit(uid, req.chatId, "weekly_report", null, "not_configured", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt)
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI 服务未配置"))
                    }
                    is AiEnhanceResult.Upstream -> call.respondUpstream(uid, req.chatId, "weekly_report", result.error, req.messages, startedAt)
                    is AiEnhanceResult.Invalid -> {
                        aiRepo.recordAudit(uid, req.chatId, "weekly_report", null, "invalid_response", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt, error = result.message)
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(result.message))
                    }
                }
            }

            post("/emotion-reply") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!RuntimeConfigService.isAiEnabled()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI temporarily disabled"))
                    return@post
                }
                if (!RuntimeConfigService.isAiSuggestRepliesEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("ai_suggest_replies_disabled"))
                    return@post
                }
                if (!aiRateLimiter.acquire(uid, maxPerMinute = 20)) {
                    aiRepo.recordAudit(uid, null, "emotion_reply", null, "rate_limited", 0, error = "rate_limited")
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 请求过于频繁"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseEnhance<EmotionReplyPayload>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (!isValidEnhanceContext(req.messages, maxMessages = MAX_EMOTION_CONTEXT_MESSAGES, maxMessageChars = MAX_ENHANCE_MESSAGE_CHARS) ||
                    req.emotion !in ALLOWED_EMOTIONS
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("情绪回复参数无效"))
                    return@post
                }
                val chatId = req.chatId?.takeIf(String::isNotBlank)
                if (chatId != null && !chatRepo.isParticipant(chatId, uid)) {
                    aiRepo.recordAudit(uid, chatId, "emotion_reply", null, "forbidden", inputChars(req.messages), error = "not_participant")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@post
                }
                if (!aiRepo.isEnabled(uid, chatId)) {
                    aiRepo.recordAudit(uid, chatId, "emotion_reply", null, "disabled", inputChars(req.messages))
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("AI 功能已关闭"))
                    return@post
                }
                val startedAt = System.currentTimeMillis()
                if (call.budgetExceeded(aiGateway, uid, req.messages, "emotion_reply", startedAt)) return@post
                when (val result = service.emotionReply(req.messages, req.emotion)) {
                    is AiEnhanceResult.Success -> {
                        aiRepo.recordAudit(uid, chatId, "emotion_reply", result.value.model, "success", inputChars(req.messages), req.messages.size, durationMs = System.currentTimeMillis() - startedAt, inputTokens = result.inputTokens, outputTokens = result.outputTokens)
                        call.respond(EmotionReplyResponse(result.value.reply, result.value.emotion, result.value.model))
                    }
                    AiEnhanceResult.NotConfigured -> {
                        aiRepo.recordAudit(uid, chatId, "emotion_reply", null, "not_configured", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt)
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI 服务未配置"))
                    }
                    is AiEnhanceResult.Upstream -> call.respondUpstream(uid, chatId, "emotion_reply", result.error, req.messages, startedAt)
                    is AiEnhanceResult.Invalid -> {
                        aiRepo.recordAudit(uid, chatId, "emotion_reply", null, "invalid_response", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt, error = result.message)
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(result.message))
                    }
                }
            }

            post("/cross-chat-qa") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!RuntimeConfigService.isAiEnabled()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI temporarily disabled"))
                    return@post
                }
                if (!RuntimeConfigService.isAiSemanticSearchEnabled() || !RuntimeConfigService.isAiGroupAssistantEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("ai_cross_chat_qa_disabled"))
                    return@post
                }
                if (!aiRateLimiter.acquire(uid, maxPerMinute = 10)) {
                    aiRepo.recordAudit(uid, null, "cross_chat_qa", null, "rate_limited", 0, error = "rate_limited")
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 请求过于频繁"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseEnhance<CrossChatQaPayload>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (!isValidCrossChatQaPayload(req)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("跨聊天问答参数无效"))
                    return@post
                }
                // 8.47：候选 chatId 必须是该用户可访问的会话——否则响应会把任意 chatId 回显为
                // 来源，且一旦未来按 messageId 服务端取文即升级为越权
                val accessibleCandidates = req.candidates.filter { candidate ->
                    candidate.chatId.isBlank() || chatRepo.isParticipant(candidate.chatId, uid)
                }
                if (accessibleCandidates.isEmpty()) {
                    aiRepo.recordAudit(uid, null, "cross_chat_qa", null, "forbidden", 0, error = "no_accessible_chat")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问候选会话"))
                    return@post
                }
                val startedAt = System.currentTimeMillis()
                if (call.budgetExceeded(aiGateway, uid, accessibleCandidates, "cross_chat_qa", startedAt)) return@post
                when (val result = service.crossChatQa(req.query, accessibleCandidates, req.chatId)) {
                    is AiEnhanceResult.Success -> {
                        aiRepo.recordAudit(uid, null, "cross_chat_qa", result.value.model, "success", inputChars(accessibleCandidates), accessibleCandidates.size, durationMs = System.currentTimeMillis() - startedAt, inputTokens = result.inputTokens, outputTokens = result.outputTokens)
                        call.respond(
                            CrossChatQaResponse(
                                answer = result.value.answer,
                                sources = result.value.sources.map { CrossChatQaSource(it.chatId, it.messageId) },
                                model = result.value.model
                            )
                        )
                    }
                    AiEnhanceResult.NotConfigured -> {
                        aiRepo.recordAudit(uid, null, "cross_chat_qa", null, "not_configured", inputChars(req.candidates), durationMs = System.currentTimeMillis() - startedAt)
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI 服务未配置"))
                    }
                    is AiEnhanceResult.Upstream -> call.respondUpstream(uid, null, "cross_chat_qa", result.error, req.candidates, startedAt)
                    is AiEnhanceResult.Invalid -> {
                        aiRepo.recordAudit(uid, null, "cross_chat_qa", null, "invalid_response", inputChars(req.candidates), durationMs = System.currentTimeMillis() - startedAt, error = result.message)
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(result.message))
                    }
                }
            }

            post("/message-classes") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!RuntimeConfigService.isAiEnabled()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI temporarily disabled"))
                    return@post
                }
                if (!RuntimeConfigService.isAiGroupAssistantEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("ai_group_assistant_disabled"))
                    return@post
                }
                if (!aiRateLimiter.acquire(uid, maxPerMinute = 20)) {
                    aiRepo.recordAudit(uid, null, "message_classes", null, "rate_limited", 0, error = "rate_limited")
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 请求过于频繁"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseEnhance<MessageClassesPayload>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (!isValidEnhanceContext(req.messages, maxMessages = MAX_CLASSIFY_MESSAGES, maxMessageChars = MAX_ENHANCE_MESSAGE_CHARS) ||
                    !isValidChatRef(req.chatId)
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("消息分类参数无效"))
                    return@post
                }
                if (!chatRepo.isParticipant(req.chatId, uid)) {
                    aiRepo.recordAudit(uid, req.chatId, "message_classes", null, "forbidden", inputChars(req.messages), error = "not_participant")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@post
                }
                if (!aiRepo.isEnabled(uid, req.chatId)) {
                    aiRepo.recordAudit(uid, req.chatId, "message_classes", null, "disabled", inputChars(req.messages))
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("AI 功能已关闭"))
                    return@post
                }
                val startedAt = System.currentTimeMillis()
                if (call.budgetExceeded(aiGateway, uid, req.messages, "message_classes", startedAt)) return@post
                when (val result = service.messageClasses(req.messages, req.chatId)) {
                    is AiEnhanceResult.Success -> {
                        aiRepo.recordAudit(uid, req.chatId, "message_classes", null, "success", inputChars(req.messages), req.messages.size, durationMs = System.currentTimeMillis() - startedAt, inputTokens = result.inputTokens, outputTokens = result.outputTokens)
                        call.respond(
                            MessageClassesResponse(
                                result.value.map { MessageClassEntry(it.category, it.count, it.confidence) }
                            )
                        )
                    }
                    AiEnhanceResult.NotConfigured -> {
                        aiRepo.recordAudit(uid, req.chatId, "message_classes", null, "not_configured", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt)
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI 服务未配置"))
                    }
                    is AiEnhanceResult.Upstream -> call.respondUpstream(uid, req.chatId, "message_classes", result.error, req.messages, startedAt)
                    is AiEnhanceResult.Invalid -> {
                        aiRepo.recordAudit(uid, req.chatId, "message_classes", null, "invalid_response", inputChars(req.messages), durationMs = System.currentTimeMillis() - startedAt, error = result.message)
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(result.message))
                    }
                }
            }
        }
        }
    }
}

// ── 校验与共享逻辑 ────────────────────────────────────────────

private const val MAX_ENHANCE_CONTEXT_MESSAGES = 40
private const val MAX_CLASSIFY_MESSAGES = 20
private const val MAX_ENHANCE_MESSAGE_CHARS = 1_200
private const val MAX_EMOTION_CONTEXT_MESSAGES = 16
private const val MAX_ENHANCE_SENDER_CHARS = 80
private const val MAX_ENHANCE_QUERY_CHARS = 300
private const val MAX_ENHANCE_CANDIDATES = 60
private const val MAX_ENHANCE_CANDIDATE_CHARS = 900
private const val MAX_ENHANCE_CHAT_ID_LENGTH = 100
private const val MAX_WEEK_SPAN_MS = 90L * 24L * 60L * 60L * 1000L

private val ALLOWED_EMOTIONS = setOf("happy", "sad", "angry", "anxious", "neutral")

private inline fun <reified T> parseEnhance(text: String): T? = try {
    if (text.isBlank()) null
    else Json { ignoreUnknownKeys = true }.decodeFromString<T>(text)
} catch (_: Exception) {
    null
}

private fun isValidChatRef(chatId: String): Boolean =
    chatId.isNotBlank() && chatId.length <= MAX_ENHANCE_CHAT_ID_LENGTH

private fun isValidEnhanceContext(messages: List<AiContextMessage>, maxMessages: Int, maxMessageChars: Int): Boolean =
    messages.isNotEmpty() &&
        messages.size <= maxMessages &&
        messages.all { message ->
            message.sender.length <= MAX_ENHANCE_SENDER_CHARS &&
                message.text.isNotBlank() &&
                message.text.length <= maxMessageChars
        }

private fun isValidWeekRange(weekStart: Long, weekEnd: Long): Boolean =
    weekStart in 0..Long.MAX_VALUE && weekEnd > weekStart &&
        weekEnd - weekStart in 1..MAX_WEEK_SPAN_MS

private fun isValidCrossChatQaPayload(req: CrossChatQaPayload): Boolean {
    val pairs = req.candidates.map { it.chatId to it.messageId }
    return req.query.isNotBlank() &&
        req.query.length <= MAX_ENHANCE_QUERY_CHARS &&
        req.candidates.isNotEmpty() &&
        req.candidates.size <= MAX_ENHANCE_CANDIDATES &&
        pairs.distinct().size == pairs.size &&
        req.candidates.all { candidate ->
            candidate.chatId.isNotBlank() &&
                candidate.chatId.length <= MAX_ENHANCE_CHAT_ID_LENGTH &&
                candidate.messageId.isNotBlank() &&
                candidate.messageId.length <= 100 &&
                candidate.sender.length <= MAX_ENHANCE_SENDER_CHARS &&
                candidate.text.isNotBlank() &&
                candidate.text.length <= MAX_ENHANCE_CANDIDATE_CHARS &&
                candidate.timestamp > 0
        }
}

private fun inputChars(messages: List<AiContextMessage>): Int =
    messages.sumOf { it.text.length }.coerceAtMost(Int.MAX_VALUE)

@JvmName("inputCharsCandidates")
private fun inputChars(candidates: List<CrossChatQaCandidate>): Int =
    candidates.sumOf { it.text.length }.coerceAtMost(Int.MAX_VALUE)

private suspend fun ApplicationCall.budgetExceeded(
    gateway: AiGateway,
    uid: String,
    messages: List<AiContextMessage>,
    feature: String,
    startedAt: Long
): Boolean {
    val estTokens = AiStreamingService.estimateTokens(messages.joinToString(" ") { it.text }).toLong()
    val budget = gateway.checkBudget(uid, estTokens)
    if (budget is BudgetResult.Exceeded) {
        response.header(HttpHeaders.RetryAfter, budget.retryAfterSeconds.toString())
        AiRepository().recordAudit(uid, null, feature, null, "budget_exceeded", inputChars(messages), durationMs = System.currentTimeMillis() - startedAt, error = "budget_exceeded")
        respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 用量已达每日上限", code = "AI_BUDGET_EXCEEDED"))
        return true
    }
    return false
}

@JvmName("budgetExceededCandidates")
private suspend fun ApplicationCall.budgetExceeded(
    gateway: AiGateway,
    uid: String,
    candidates: List<CrossChatQaCandidate>,
    feature: String,
    startedAt: Long
): Boolean {
    val estTokens = AiStreamingService.estimateTokens(candidates.joinToString(" ") { it.text }).toLong()
    val budget = gateway.checkBudget(uid, estTokens)
    if (budget is BudgetResult.Exceeded) {
        response.header(HttpHeaders.RetryAfter, budget.retryAfterSeconds.toString())
        AiRepository().recordAudit(uid, null, feature, null, "budget_exceeded", inputChars(candidates), durationMs = System.currentTimeMillis() - startedAt, error = "budget_exceeded")
        respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI 用量已达每日上限", code = "AI_BUDGET_EXCEEDED"))
        return true
    }
    return false
}

private suspend fun ApplicationCall.respondUpstream(
    uid: String,
    chatId: String?,
    feature: String,
    error: AiGatewayResult.UpstreamError,
    messages: List<AiContextMessage>,
    startedAt: Long
) {
    AiRepository().recordAudit(uid, chatId, feature, null, "upstream_error", inputChars(messages), durationMs = System.currentTimeMillis() - startedAt, error = error.message)
    // 8.31 运维修复：AI 上游失败此前只写审计表，应用日志零痕迹 → 加结构化日志行
    org.slf4j.LoggerFactory.getLogger("AiUpstream")
        .error("AI upstream failed [feature={}] [userId={}] [statusCode={}] [durationMs={}] : {}",
            feature, uid, error.statusCode,
            System.currentTimeMillis() - startedAt, error.message?.take(300))
    if (error.retryAfterSeconds != null) {
        response.header(HttpHeaders.RetryAfter, error.retryAfterSeconds.toString())
    }
    respond(HttpStatusCode.BadGateway, ErrorResponse("AI 服务暂时不可用，请稍后重试"))
}

@JvmName("respondUpstreamCandidates")
private suspend fun ApplicationCall.respondUpstream(
    uid: String,
    chatId: String?,
    feature: String,
    error: AiGatewayResult.UpstreamError,
    candidates: List<CrossChatQaCandidate>,
    startedAt: Long
) {
    AiRepository().recordAudit(uid, chatId, feature, null, "upstream_error", inputChars(candidates), durationMs = System.currentTimeMillis() - startedAt, error = error.message)
    // 8.31 运维修复：同上，AI 上游失败留应用日志
    org.slf4j.LoggerFactory.getLogger("AiUpstream")
        .error("AI upstream failed [feature={}] [userId={}] [statusCode={}] [durationMs={}] : {}",
            feature, uid, error.statusCode,
            System.currentTimeMillis() - startedAt, error.message?.take(300))
    if (error.retryAfterSeconds != null) {
        response.header(HttpHeaders.RetryAfter, error.retryAfterSeconds.toString())
    }
    respond(HttpStatusCode.BadGateway, ErrorResponse("AI 服务暂时不可用，请稍后重试"))
}

// ── 请求/响应 DTO（白名单字段） ───────────────────────────────

@Serializable
private data class ConversationProfilePayload(
    val messages: List<AiContextMessage>,
    val chatId: String
)

@Serializable
private data class ConversationProfileResponse(val summary: String, val model: String)

@Serializable
private data class WeeklyReportPayload(
    val messages: List<AiContextMessage>,
    val weekStart: Long,
    val weekEnd: Long,
    val chatId: String
)

@Serializable
private data class WeeklyReportResponse(val report: String, val model: String)

@Serializable
private data class EmotionReplyPayload(
    val messages: List<AiContextMessage>,
    val emotion: String,
    val chatId: String? = null
)

@Serializable
private data class EmotionReplyResponse(val reply: String, val emotion: String, val model: String)

@Serializable
private data class CrossChatQaPayload(
    val query: String,
    val candidates: List<CrossChatQaCandidate>,
    val chatId: String
)

@Serializable
private data class CrossChatQaSource(val chatId: String, val messageId: String)

@Serializable
private data class CrossChatQaResponse(
    val answer: String,
    val sources: List<CrossChatQaSource> = emptyList(),
    val model: String? = null
)

@Serializable
private data class MessageClassesPayload(
    val messages: List<AiContextMessage>,
    val chatId: String
)

@Serializable
private data class MessageClassEntry(val category: String, val count: Int, val confidence: Double)

@Serializable
private data class MessageClassesResponse(val classes: List<MessageClassEntry>)
