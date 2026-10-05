package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.network.AiContextMessage
import com.maodouchat.util.RuntimeFlags
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 离线建议（G166 从 ChatDetailAiGeneration.kt 抽出，675 行）。
 *
 * `buildOfflineAiSuggestions` 按最后一条消息的关键词命中本地话术库，
 * 给出最多 4 条草稿建议。它不访问网络，所以 AI 服务不可用时用户仍能得到可用回复。
 * 话术库主体已下沉为纯对象 `OfflineSuggestionPhrases`，本文件只留 VM 接线（门控 + 文本预处理）。
 *
 * 抽出理由：原文件 1975 行且已顶到在监上限；这一块与
 * 「请求 / 流式 / 取消」逻辑无耦合，是最容易安全切分的大块。
 */
internal fun ChatDetailViewModel.generateAiSuggestions(tone: String = "friendly") {
    val contextMessages = buildAiContextMessages(limit = 16)
    if (contextMessages.isEmpty()) return
    val request = captureAiRequestSnapshot()
    if (request == null) {
        _uiState.update { it.copy(aiReplyStreamErrorCode = AiOperationError.CONTEXT_MISSING) }
        return
    }
    aiReplyStreamJob?.cancel()
    val generation = aiReplyGate.next()
    val safeTone = when (tone.trim().lowercase()) {
        "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
        else -> "friendly"
    }
    _uiState.update {
        it.copy(
            isAiWorking = true,
            isAiReplyStreaming = true,
            aiReplyStreamErrorCode = null,
            aiSuggestions = emptyList(),
            groupEncryptionWarning = null
        )
    }
    aiReplyStreamJob = viewModelScope.launch {
        try {
            requireAiRequestCurrent(request)
            com.maodouchat.ai.agent.LocalAiGateway.suggestReplies(
                getApplication(),
                contextMessages,
                safeTone,
                4
            ).fold(
                onSuccess = { replies ->
                    if (!aiReplyGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                    _uiState.update {
                        it.copy(
                            aiSuggestions = replies.map { reply -> reply.take(500) }.filter { it.isNotBlank() }.take(4),
                            isAiReplyStreaming = false,
                            isAiWorking = false,
                            aiReplyStreamErrorCode = if (replies.isEmpty()) AiOperationError.EMPTY_RESULT else null
                        )
                    }
                },
                onFailure = { error ->
                    if (!aiReplyGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                    val offline = buildOfflineAiSuggestions(contextMessages, safeTone)
                    _uiState.update {
                        it.copy(
                            isAiReplyStreaming = false,
                            isAiWorking = false,
                            aiSuggestions = offline.ifEmpty { it.aiSuggestions },
                            aiReplyStreamErrorCode = if (offline.isNotEmpty()) null else aiOperationErrorCode(error),
                            groupEncryptionWarning = if (offline.isNotEmpty()) {
                                text(R.string.ai_offline_suggestions_hint)
                            } else it.groupEncryptionWarning
                        )
                    }
                }
            )
            // Empty upstream result -> offline heuristic fallback
            if (aiReplyGate.isCurrent(generation) &&
                isAiRequestCurrent(request) &&
                _uiState.value.aiSuggestions.isEmpty() &&
                _uiState.value.aiReplyStreamErrorCode == AiOperationError.EMPTY_RESULT
            ) {
                val offline = buildOfflineAiSuggestions(contextMessages, safeTone)
                if (offline.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            aiSuggestions = offline,
                            aiReplyStreamErrorCode = null,
                            groupEncryptionWarning = text(R.string.ai_offline_suggestions_hint)
                        )
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (aiReplyGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                _uiState.update {
                    it.copy(isAiReplyStreaming = false, isAiWorking = false)
                }
            }
            throw error
        }
    }
}

/** Local, privacy-preserving reply chips when cloud AI is unavailable. */
private fun ChatDetailViewModel.buildOfflineAiSuggestions(
    contextMessages: List<com.maodouchat.network.AiContextMessage>,
    tone: String
): List<String> {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) return emptyList()
    val texts = contextMessages.map { it.text.trim() }.filter { it.isNotBlank() }
    if (texts.isEmpty()) return emptyList()
    val seed = texts.last().take(120)
    val recent = texts.takeLast(3).joinToString(" ").take(240)
    val lower = (seed + " " + recent).lowercase()
    return OfflineSuggestionPhrases.suggest(lower, seed, tone)
}
