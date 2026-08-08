package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class AiRewriteRequest(
    val text: String,
    val mode: String = "polish",
    val targetLanguage: String? = null,
    val chatId: String? = null,
    /** Optional writing-style preference hint from client prefs (untrusted preference text). */
    val styleHint: String? = null
)

@Serializable
data class AiRewriteResponse(val text: String, val model: String)

@Serializable
data class AiTranslateRequest(val text: String, val targetLanguage: String = "中文", val chatId: String? = null)

@Serializable
data class AiTranslateResponse(val text: String, val model: String, val targetLanguage: String)

@Serializable
data class AiContextMessage(val sender: String = "", val text: String)

@Serializable
data class AiSuggestRepliesRequest(
    val messages: List<AiContextMessage>,
    val tone: String = "natural",
    val count: Int = 3,
    val chatId: String? = null
)

@Serializable
data class AiSuggestRepliesResponse(val replies: List<String>, val model: String)

@Serializable
data class AiStreamEvent(
    val type: String,
    val text: String? = null,
    val model: String? = null,
    val code: String? = null
)

@Serializable
data class AiSummarizeRequest(
    val messages: List<AiContextMessage>,
    val style: String = "brief",
    val chatId: String? = null
)

@Serializable
data class AiSummarizeResponse(val summary: String, val model: String)

@Serializable
data class AiSummarySyncUploadRequest(
    val syncId: String,
    val senderDeviceId: Int,
    val targetDeviceIds: List<Int>,
    val envelope: String
)

@Serializable
data class AiSummarySyncEnvelopeDto(
    val id: String,
    val syncId: String,
    val senderDeviceId: Int,
    val envelope: String,
    val createdAt: Long
)

@Serializable
data class AiSummarySyncAckRequest(val deviceId: Int, val envelopeIds: List<String>)

@Serializable
data class AiSummarySyncStatusResponse(
    val status: String = "ok",
    val stored: Int = 0,
    val removed: Int = 0
)

@Serializable
data class AiGroupAssistantRequest(
    val query: String,
    val messages: List<AiContextMessage>,
    val mode: String = "answer",
    val chatId: String
)

@Serializable
data class AiGroupTask(
    val title: String,
    val owner: String? = null,
    val dueText: String? = null,
    val dueAt: Long? = null
)

@Serializable
data class AiGroupAssistantResponse(
    val answer: String,
    val mode: String,
    val model: String,
    val tasks: List<AiGroupTask> = emptyList()
)

@Serializable
data class AiSemanticSearchCandidate(
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
)

@Serializable
data class AiSemanticSearchRequest(
    val query: String,
    val candidates: List<AiSemanticSearchCandidate>,
    val limit: Int = 10,
    val chatId: String
)

@Serializable
data class AiSemanticSearchMatch(val messageId: String, val score: Double)

@Serializable
data class AiSemanticSearchResponse(val matches: List<AiSemanticSearchMatch>, val model: String)

@Serializable
data class AiGlobalSemanticSearchCandidate(
    val chatId: String,
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
)

@Serializable
data class AiGlobalSemanticSearchRequest(
    val query: String,
    val candidates: List<AiGlobalSemanticSearchCandidate>,
    val limit: Int = 20
)

@Serializable
data class AiGlobalSemanticSearchMatch(val chatId: String, val messageId: String, val score: Double)

@Serializable
data class AiGlobalSemanticSearchResponse(val matches: List<AiGlobalSemanticSearchMatch>, val model: String)

@Serializable
data class AiTranscribeRequest(
    val audioBase64: String,
    val mimeType: String = "audio/mp4",
    val language: String? = null,
    val chatId: String? = null
)

@Serializable
data class AiTranscribeResponse(val text: String, val model: String)

@Serializable
data class AiImageAnalyzeRequest(
    val imageBase64: String,
    val mimeType: String = "image/jpeg",
    val mode: String,
    val chatId: String
)

@Serializable
data class AiImageAnalyzeResponse(val text: String, val mode: String, val model: String)

@Serializable
data class AiFileAnalyzeRequest(
    val fileBase64: String,
    val fileName: String,
    val mimeType: String,
    val mode: String,
    val question: String? = null,
    val chatId: String
)

@Serializable
data class AiFileAnalyzeResponse(
    val text: String,
    val mode: String,
    val fileName: String,
    val model: String
)

@Serializable
data class AiSettingsRequest(val chatId: String? = null, val enabled: Boolean)

@Serializable
data class AiSettingsResponse(
    val userEnabled: Boolean = true,
    val chatId: String? = null,
    val chatEnabled: Boolean? = null,
    val effectiveEnabled: Boolean = true
)

@Serializable
data class AiAuditLogResponse(
    val id: String,
    val chatId: String? = null,
    val feature: String,
    val model: String? = null,
    val status: String,
    val inputChars: Int = 0,
    val contextMessages: Int = 0,
    val durationMs: Long? = null,
    val error: String? = null,
    val createdAt: Long
)

// ── B4 · 会话画像 ─────────────────────────────────────────────
@Serializable
data class AiConversationProfileRequest(
    val messages: List<AiContextMessage>,
    val chatId: String
)

@Serializable
data class AiConversationProfileResponse(val summary: String, val model: String)

// ── B4 · 群周报 ───────────────────────────────────────────────
@Serializable
data class AiWeeklyReportRequest(
    val messages: List<AiContextMessage>,
    val weekStart: Long,
    val weekEnd: Long,
    val chatId: String
)

@Serializable
data class AiWeeklyReportResponse(val report: String, val model: String)

// ── B4 · 情绪感知回复 ─────────────────────────────────────────
@Serializable
data class AiEmotionReplyRequest(
    val messages: List<AiContextMessage>,
    val emotion: String = "neutral",
    val chatId: String? = null
)

@Serializable
data class AiEmotionReplyResponse(val reply: String, val emotion: String, val model: String)

// ── 跨聊天问答（已由 GlobalSearchScreen 的 globalSemanticSearch 取代，8.47 移除双实现）──
