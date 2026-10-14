package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.model.Message

internal sealed class PendingAiAction {
    data class Rewrite(val mode: String, val targetLanguage: String? = null) : PendingAiAction()
    data class SuggestReplies(val tone: String = "friendly") : PendingAiAction()
    data class Summarize(
        val scope: AiSummaryScope,
        val messages: List<Message>,
        val style: String = "brief"
    ) : PendingAiAction()
    data class TranscribeVoice(val messageId: String) : PendingAiAction()
    data class TranslateMessage(val messageId: String, val targetLanguage: String) : PendingAiAction()
    data class AnalyzeImage(val messageId: String, val mode: AiImageAnalysisMode) : PendingAiAction()
    data class AnalyzeFile(val messageId: String, val mode: AiFileAnalysisMode, val question: String?) : PendingAiAction()
    data class SemanticSearch(val query: String, val candidateMessageIds: List<String>) : PendingAiAction()
    data class GroupAssistant(val query: String, val mode: String) : PendingAiAction()
}

internal sealed interface AiOperationPreparation {
    data object Untracked : AiOperationPreparation
    data object InvalidContext : AiOperationPreparation
    data class Persisted(val operation: AiOperationEntity) : AiOperationPreparation
}

/** 附带 retry-after 的错误码解析；[persistedCode] 可写入 Room lastErrorCode。 */
internal data class AiClassifiedError(
    val code: String,
    val retryAfterSeconds: Long? = null,
    val persistedCode: String = code
)
