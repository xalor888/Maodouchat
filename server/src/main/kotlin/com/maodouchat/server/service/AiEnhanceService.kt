package com.maodouchat.server.service

import com.maodouchat.server.model.AiContextMessage
import com.maodouchat.server.model.AiGroupAssistantResult
import com.maodouchat.server.model.AiSemanticSearchCandidate
import com.maodouchat.server.model.AiSemanticSearchMatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * B4 · AI 增强能力服务端编排（复用 AiGateway）。
 *
 * 服务端不落库任何会话明文，也不读取加密库；仅对客户端提交的白名单消息做「消毒 →
 * 网关编排 → 白名单输出」的尽力转发，Prompt 安全策略与客户端 AiPromptSafetyPolicy 同类
 * （AiGateway 各方法内部已声明 untrusted data / 不执行特权动作）。
 *
 * 六个能力：
 * - conversationProfile：复用 AiGateway.summarize（detailed 风格叙事）
 * - weeklyReport：复用 AiGateway.groupAssistant（tasks 风格结构化周报）
 * - emotionReply：复用 AiGateway.suggestReplies（按情绪映射白名单语气）
 * - crossChatQa：复用 AiGateway.semanticSearch（跨会话重排）+ groupAssistant（生成答案）
 * - messageClasses：复用 AiGateway.groupAssistant（summary 风格抽取类别），类别经白名单归一
 *
 * 输出模型（ConversationProfileOutput / WeeklyReportOutput / EmotionReplyOutput /
 * CrossChatQaOutput / MessageClassOutput）见本文件下方；路由层 DTO 见 AiEnhanceRouting.kt。
 */
class AiEnhanceService(
    private val gateway: AiGateway
) {

    suspend fun conversationProfile(
        messages: List<AiContextMessage>,
        chatId: String
    ): AiEnhanceResult<ConversationProfileOutput> {
        val safe = sanitizeMessages(messages, MAX_CONTEXT_MESSAGES, MAX_MESSAGE_CHARS)
        if (safe.isEmpty()) return AiEnhanceResult.Invalid("无有效上下文")
        return when (val result = gateway.summarize(safe, "detailed")) {
            is AiGatewayResult.Success ->
                AiEnhanceResult.Success(ConversationProfileOutput(result.value.take(MAX_OUTPUT_CHARS), result.model), result.inputTokens, result.outputTokens)
            AiGatewayResult.NotConfigured -> AiEnhanceResult.NotConfigured
            is AiGatewayResult.UpstreamError -> AiEnhanceResult.Upstream(result)
            is AiGatewayResult.InvalidResponse -> AiEnhanceResult.Invalid(result.message)
        }
    }

    suspend fun weeklyReport(
        messages: List<AiContextMessage>,
        weekStart: Long,
        weekEnd: Long
    ): AiEnhanceResult<WeeklyReportOutput> {
        val safe = sanitizeMessages(messages, MAX_CONTEXT_MESSAGES, MAX_MESSAGE_CHARS)
        if (safe.isEmpty()) return AiEnhanceResult.Invalid("无有效上下文")
        val prompt = "本周（${weekStart} 至 ${weekEnd}）群聊回顾"
        val result = gateway.groupAssistant(
            query = prompt,
            messages = safe,
            mode = "tasks"
        )
        return when (result) {
            is AiGatewayResult.Success -> {
                val report = renderWeeklyReport(result.value)
                AiEnhanceResult.Success(WeeklyReportOutput(report, result.model), result.inputTokens, result.outputTokens)
            }
            AiGatewayResult.NotConfigured -> AiEnhanceResult.NotConfigured
            is AiGatewayResult.UpstreamError -> AiEnhanceResult.Upstream(result)
            is AiGatewayResult.InvalidResponse -> AiEnhanceResult.Invalid(result.message)
        }
    }

    suspend fun emotionReply(
        messages: List<AiContextMessage>,
        emotion: String
    ): AiEnhanceResult<EmotionReplyOutput> {
        val safe = sanitizeMessages(messages, MAX_CONTEXT_MESSAGES, MAX_MESSAGE_CHARS)
        if (safe.isEmpty()) return AiEnhanceResult.Invalid("无有效上下文")
        val tone = emotionToTone(emotion)
        val replyResult = gateway.suggestReplies(safe, tone, count = 1)
        return when (replyResult) {
            is AiGatewayResult.Success -> {
                val reply = replyResult.value.firstOrNull()?.take(MAX_OUTPUT_CHARS).orEmpty()
                if (reply.isBlank()) AiEnhanceResult.Invalid("情绪回复为空")
                else AiEnhanceResult.Success(EmotionReplyOutput(reply, emotion, replyResult.model), replyResult.inputTokens, replyResult.outputTokens)
            }
            AiGatewayResult.NotConfigured -> AiEnhanceResult.NotConfigured
            is AiGatewayResult.UpstreamError -> AiEnhanceResult.Upstream(replyResult)
            is AiGatewayResult.InvalidResponse -> AiEnhanceResult.Invalid(replyResult.message)
        }
    }

    suspend fun crossChatQa(
        query: String,
        candidates: List<CrossChatQaCandidate>,
        chatId: String
    ): AiEnhanceResult<CrossChatQaOutput> {
        val safeQuery = query.trim().take(MAX_QUERY_CHARS)
        if (safeQuery.isBlank()) return AiEnhanceResult.Invalid("问题为空")
        val safeCandidates = candidates
            .take(MAX_CANDIDATES)
            .mapNotNull { candidate ->
                val text = candidate.text.trim().take(MAX_CANDIDATE_CHARS)
                if (text.isBlank()) null
                else candidate.copy(sender = candidate.sender.trim().take(MAX_SENDER_CHARS), text = text)
            }
            .distinctBy { it.messageId }
        if (safeCandidates.isEmpty()) return AiEnhanceResult.Invalid("无有效候选")
        val allowedIds = safeCandidates.mapTo(hashSetOf()) { it.messageId }

        // 第一跳：逐会话语义重排（结果白名单 = 请求候选 ID 子集）。
        // 8.52 修复 AI-2：并行化各会话语义重排 + 会话数上限 + 整体超时——原逐 chatId 串行
        //（最多 60 次 LLM 往返）命中 60s 读超时，且客户端放弃后服务端仍继续付费
        val perChat = safeCandidates.groupBy { it.chatId }.toList().take(MAX_CROSS_CHAT_CHATS)
        val perChatResults = kotlinx.coroutines.withTimeoutOrNull(CROSS_CHAT_TOTAL_TIMEOUT_MS) {
            kotlinx.coroutines.coroutineScope {
                perChat.map { (_, group) ->
                    async {
                        gateway.semanticSearch(
                            safeQuery,
                            group.map { candidate ->
                                AiSemanticSearchCandidate(
                                    messageId = candidate.messageId,
                                    sender = candidate.sender,
                                    text = candidate.text,
                                    timestamp = candidate.timestamp
                                )
                            },
                            limit = TOP_MATCHES_PER_CHAT
                        )
                    }
                }.awaitAll()
            }
        } ?: return AiEnhanceResult.Upstream(AiGatewayResult.UpstreamError(408, "AI 处理超时"))
        val matches = mutableListOf<AiSemanticSearchMatch>()
        var lastModel: String? = null
        for (result in perChatResults) {
            when (result) {
                is AiGatewayResult.Success -> {
                    matches += result.value
                    lastModel = result.model
                }
                AiGatewayResult.NotConfigured -> return AiEnhanceResult.NotConfigured
                is AiGatewayResult.UpstreamError -> return AiEnhanceResult.Upstream(result)
                is AiGatewayResult.InvalidResponse -> return AiEnhanceResult.Invalid(result.message)
            }
        }
        val ranked = matches
            .distinctBy { it.messageId }
            .filter { it.messageId in allowedIds }
            .sortedByDescending { it.score }
            .take(MAX_QA_CONTEXT)
        if (ranked.isEmpty()) return AiEnhanceResult.Invalid("未找到相关消息")

        // 第二跳：基于白名单结果生成答案。
        val byId = safeCandidates.associateBy { it.messageId }
        val contextMessages = ranked.mapNotNull { match -> byId[match.messageId] }.map { candidate ->
            AiContextMessage(sender = candidate.sender, text = candidate.text)
        }
        val answerResult = gateway.groupAssistant(
            query = safeQuery,
            messages = contextMessages,
            mode = "answer"
        )
        return when (answerResult) {
            is AiGatewayResult.Success -> {
                val sources = ranked.map { match ->
                    val candidate = byId[match.messageId]
                    CrossChatQaSource(
                        chatId = candidate?.chatId.orEmpty(),
                        messageId = match.messageId
                    )
                }.filter { it.chatId.isNotBlank() }
                AiEnhanceResult.Success(
                    CrossChatQaOutput(
                        answer = answerResult.value.answer.trim().take(MAX_ANSWER_CHARS),
                        sources = sources,
                        model = answerResult.model ?: lastModel
                    ),
                    answerResult.inputTokens,
                    answerResult.outputTokens
                )
            }
            AiGatewayResult.NotConfigured -> AiEnhanceResult.NotConfigured
            is AiGatewayResult.UpstreamError -> AiEnhanceResult.Upstream(answerResult)
            is AiGatewayResult.InvalidResponse -> AiEnhanceResult.Invalid(answerResult.message)
        }
    }

    suspend fun messageClasses(
        messages: List<AiContextMessage>,
        chatId: String
    ): AiEnhanceResult<List<MessageClassOutput>> {
        val safe = sanitizeMessages(messages, MAX_CLASSIFY_MESSAGES, MAX_MESSAGE_CHARS)
        if (safe.isEmpty()) return AiEnhanceResult.Invalid("无有效上下文")
        val prompt = "把以下群聊消息按类别归并"
        val result = gateway.groupAssistant(
            query = prompt,
            messages = safe,
            mode = "summary"
        )
        return when (result) {
            is AiGatewayResult.Success -> {
                val classes = normalizeClasses(result.value.answer)
                if (classes.isEmpty()) AiEnhanceResult.Invalid("分类结果无效")
                else AiEnhanceResult.Success(classes, result.inputTokens, result.outputTokens)
            }
            AiGatewayResult.NotConfigured -> AiEnhanceResult.NotConfigured
            is AiGatewayResult.UpstreamError -> AiEnhanceResult.Upstream(result)
            is AiGatewayResult.InvalidResponse -> AiEnhanceResult.Invalid(result.message)
        }
    }

    private fun sanitizeMessages(
        messages: List<AiContextMessage>,
        maxMessages: Int,
        maxChars: Int
    ): List<AiContextMessage> =
        messages.take(maxMessages).mapNotNull { message ->
            val text = message.text.trim().take(maxChars)
            if (text.isBlank()) null
            else AiContextMessage(sender = message.sender.trim().take(MAX_SENDER_CHARS), text = text)
        }

    private fun emotionToTone(emotion: String): String = when (emotion) {
        "happy" -> "warm"
        "sad" -> "empathetic"
        "angry" -> "gentle"
        "anxious" -> "encouraging"
        else -> "natural"
    }

    /** 白名单输出：只把模型文本按已归一类别映射为结构化行，类别名严格受控。 */
    private fun normalizeClasses(text: String): List<MessageClassOutput> {
        val counts = ALLOWED_CLASSES.associateWith { 0 }.toMutableMap()
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val matched = ALLOWED_CLASSES.firstOrNull { allowed ->
                line.contains(allowed, ignoreCase = true) || line.startsWith(allowed, ignoreCase = true)
            } ?: continue
            val digit = Regex("""(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            counts[matched] = counts[matched]!! + digit.coerceIn(0, 1_000)
        }
        val total = counts.values.sum()
        if (total == 0) return emptyList()
        return counts.map { (category, count) ->
            MessageClassOutput(category = category, count = count, confidence = count.toDouble() / total)
        }.sortedByDescending { it.count }
    }

    private fun renderWeeklyReport(result: AiGroupAssistantResult): String {
        val tasks = result.tasks.take(MAX_REPORT_TASKS)
        val lines = mutableListOf<String>()
        if (result.answer.isNotBlank()) lines += result.answer.trim().take(2_000)
        if (tasks.isNotEmpty()) {
            lines += "本周待办与进展："
            tasks.forEach { task ->
                val owner = task.owner?.trim()?.takeIf(String::isNotBlank)
                val due = task.dueText?.trim()?.takeIf(String::isNotBlank)
                val suffix = listOfNotNull(owner?.let { "负责人：$it" }, due?.let { "期限：$it" }).joinToString("，")
                lines += "- ${task.title.trim().take(300)}${if (suffix.isNotBlank()) "（$suffix）" else ""}"
            }
        }
        return lines.joinToString("\n").take(MAX_OUTPUT_CHARS)
    }

    companion object {
        const val MAX_CONTEXT_MESSAGES = 40
        const val MAX_CLASSIFY_MESSAGES = 20
        const val MAX_MESSAGE_CHARS = 1_200
        const val MAX_SENDER_CHARS = 80
        const val MAX_QUERY_CHARS = 300
        const val MAX_CANDIDATES = 60
        const val MAX_CANDIDATE_CHARS = 900
        const val MAX_QA_CONTEXT = 20
        const val MAX_ANSWER_CHARS = 4_000
        const val MAX_OUTPUT_CHARS = 6_000
        const val MAX_REPORT_TASKS = 20
        const val TOP_MATCHES_PER_CHAT = 10
        /** 8.52 修复 AI-2：跨聊天问答最多涉及的会话数（防 60 次串行 LLM 往返）。 */
        const val MAX_CROSS_CHAT_CHATS = 8
        /** 8.52 修复 AI-2：跨聊天问答整体超时（毫秒），超时终止并返回 408。 */
        const val CROSS_CHAT_TOTAL_TIMEOUT_MS = 30_000L
        val ALLOWED_CLASSES = listOf(
            "notice", "todo", "finance", "study", "tech", "social"
        )
    }
}

/** B4 · 增强能力模型（路由层 DTO 见 AiEnhanceRouting.kt；此处仅服务编排输出模型）。 */
data class ConversationProfileOutput(val summary: String, val model: String)
data class WeeklyReportOutput(val report: String, val model: String)
data class EmotionReplyOutput(val reply: String, val emotion: String, val model: String)
data class CrossChatQaSource(val chatId: String, val messageId: String)
data class CrossChatQaOutput(val answer: String, val sources: List<CrossChatQaSource>, val model: String?)
data class MessageClassOutput(val category: String, val count: Int, val confidence: Double)

/**
 * 跨聊天问答候选消息（携带 chatId，供逐会话重排与来源引用；服务端不落库）。
 * 路由层 DTO 与客户端字段一致（chatId/messageId/sender/text/timestamp）。
 */
@kotlinx.serialization.Serializable
data class CrossChatQaCandidate(
    val chatId: String,
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
)

/** B4 · 增强能力统一结果（仅服务端内部；路由层映射为 HTTP 状态）。 */
sealed interface AiEnhanceResult<out T> {
    // 8.46：Success 携带 input/output tokens，路由层成功审计时落库——
    // 否则 enhance 五能力从不计 token，每日预算（sumTokensForUserToday）恒 0 被绕过
    data class Success<T>(
        val value: T,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null
    ) : AiEnhanceResult<T>
    data object NotConfigured : AiEnhanceResult<Nothing>
    data class Upstream(val error: AiGatewayResult.UpstreamError) : AiEnhanceResult<Nothing>
    data class Invalid(val message: String) : AiEnhanceResult<Nothing>
}
