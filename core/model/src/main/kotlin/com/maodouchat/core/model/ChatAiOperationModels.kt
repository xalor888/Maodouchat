package com.maodouchat.core.model

sealed class PendingAiAction {
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
