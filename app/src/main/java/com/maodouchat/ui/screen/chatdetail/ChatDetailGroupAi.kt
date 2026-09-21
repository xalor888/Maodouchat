package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.Message
import com.maodouchat.network.AiContextMessage
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.network.AiSemanticSearchCandidate
import com.maodouchat.data.model.semanticSearchText
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群 AI 助手与语义搜索（G170 从 ChatDetailAiGeneration.kt 抽出，218 行）。
 *
 * 安全约束（代码里已 enforce，改动时勿丢）：
 * **密聊会话禁止群 AI 助手**——解密明文不得送服务端 AI。
 *
 * `inferGroupAiMode` / `parseGroupAiCommand` 是纯本地判定：
 * 从用户输入里推断助手模式与解析「/命令 参数」形式。
 *
 * 抽出理由：与「请求 / 流式 / 取消 / 建议 / 总结」逻辑无耦合。
 */
internal fun ChatDetailViewModel.groupAssistant(query: String, mode: String) {
    // 密聊会话禁止群 AI 助手：解密明文不得送服务端 AI
    if (_uiState.value.isSecretChat == true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_group_assistant_disabled)) }
        return
    }
    val contextMessages = buildGroupAiContextMessages()
    if (contextMessages.isEmpty()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_no_context)) }
        return
    }
    val request = captureAiRequestSnapshot()
    if (request == null) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
        return
    }
    groupAiJob?.cancel()
    val generation = groupAiGate.next()
    val currentCommand = parseGroupAiCommand(_uiState.value.inputText)
    _uiState.update {
        it.copy(
            inputText = if (currentCommand == query) "" else it.inputText,
            isAiWorking = true,
            groupAiAnswer = null,
            groupAiQuestion = query,
            groupAiMode = mode,
            groupAiTasks = emptyList(),
            isSavingGroupAiTasks = false,
            groupAiTasksSaved = false,
            groupAiTaskSaveError = null,
            groupAiAnswerShared = false,
            groupEncryptionWarning = null
        )
    }
    if (currentCommand == query) clearDraft()
    val job = viewModelScope.launch {
        try {
            if (!groupAiGate.isCurrent(generation)) return@launch
            requireAiRequestCurrent(request)
            com.maodouchat.ai.agent.LocalAiGateway.groupAssistant(
                getApplication(),
                query,
                contextMessages,
                mode
            ).fold(
                onSuccess = { answer ->
                    if (!groupAiGate.isCurrent(generation)) return@fold
                    requireAiRequestCurrent(request)
                    val tasks = if (mode == "tasks") {
                        answer.lineSequence()
                            .map { it.trim().trimStart('-', '*', '•') }
                            .filter { it.isNotBlank() }
                            .take(30)
                            .map { line ->
                                com.maodouchat.network.AiGroupTask(title = line.take(300))
                            }
                            .toList()
                    } else emptyList()
                    _uiState.update {
                        it.copy(
                            groupAiAnswer = com.maodouchat.ai.AiPromptSafetyPolicy
                                .annotateIfPrivilegedHallucination(
                                    answer.trim().take(4_000),
                                    text(R.string.chat_ai_privilege_hallucination_disclaimer)
                                ),
                            groupAiMode = mode,
                            groupAiTasks = tasks,
                            isAiWorking = false
                        )
                    }
                },
                onFailure = { error ->
                    if (!groupAiGate.isCurrent(generation)) return@fold
                    requireAiRequestCurrent(request)
                    _uiState.update {
                        it.copy(
                            inputText = if (it.inputText.isBlank()) "@AI $query" else it.inputText,
                            isAiWorking = false,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_group_ai_failed)
                        )
                    }
                    scheduleDraftPersistence(_uiState.value.inputText)
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (groupAiGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                _uiState.update { it.copy(isAiWorking = false) }
            }
            throw error
        }
    }
    groupAiJob = job
    job.invokeOnCompletion {
        if (groupAiJob === job) groupAiJob = null
    }
}

internal fun ChatDetailViewModel.semanticSearch(query: String, candidateMessageIds: List<String>) {
    val candidateIdSet = candidateMessageIds.toHashSet()
    val state = _uiState.value
    val candidates = state.messages
        .asSequence()
        .filter { it.id in candidateIdSet }
        .mapNotNull { message ->
            val searchableText = message.semanticSearchText()
            if (searchableText.isBlank()) return@mapNotNull null
            val sender = if (message.senderId == currentUserId) {
                "me"
            } else {
                state.chat?.participants?.firstOrNull { it.id == message.senderId }?.displayName
                    ?: state.contact.displayName
            }
            AiSemanticSearchCandidate(
                messageId = message.id,
                sender = sender.take(80),
                text = searchableText.take(700),
                timestamp = message.timestamp
            )
        }
        .take(80)
        .toList()
    if (candidates.isEmpty()) {
        _uiState.update { it.copy(semanticSearchError = text(R.string.chat_semantic_search_no_context)) }
        return
    }
    val request = captureAiRequestSnapshot()
    if (request == null) {
        _uiState.update { it.copy(semanticSearchError = text(R.string.error_session_expired)) }
        return
    }
    semanticSearchJob?.cancel()
    val generation = semanticSearchGate.next()
    val job = viewModelScope.launch {
        _uiState.update {
            it.copy(
                semanticSearchResultIds = emptyList(),
                semanticSearchQuery = query,
                isSemanticSearching = true,
                semanticSearchError = null
            )
        }
        try {
            requireAiRequestCurrent(request)
            val ranked = com.maodouchat.ai.agent.LocalAiGateway.rankSemantic(
                query,
                candidates.map { it.messageId to it.text }
            )
            requireAiRequestCurrent(request)
            if (!semanticSearchGate.isCurrent(generation)) return@launch
            val allowedIds = candidates.mapTo(hashSetOf()) { it.messageId }
            val resultIds = ranked.filter { it in allowedIds }.distinct().take(12)
            _uiState.update {
                it.copy(
                    semanticSearchResultIds = resultIds,
                    semanticSearchQuery = query,
                    isSemanticSearching = false,
                    semanticSearchError = if (resultIds.isEmpty()) {
                        text(R.string.chat_semantic_search_no_context)
                    } else null
                )
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (semanticSearchGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                _uiState.update {
                    it.copy(
                        isSemanticSearching = false,
                        semanticSearchError = error.message ?: text(R.string.chat_semantic_search_failed)
                    )
                }
            }
        }
    }
    semanticSearchJob = job
    job.invokeOnCompletion { error ->
        if (semanticSearchJob === job) semanticSearchJob = null
        // Cancelled mid-request (clear / newer generation) must not leave spinner stuck.
        if (error is kotlinx.coroutines.CancellationException &&
            semanticSearchGate.isCurrent(generation) &&
            isAiRequestCurrent(request) &&
            _uiState.value.isSemanticSearching
        ) {
            _uiState.update { it.copy(isSemanticSearching = false) }
        }
    }
}

internal fun ChatDetailViewModel.buildGroupAiContextMessages(): List<AiContextMessage> {
    return buildPlainAiContextMessages(
        messages = _uiState.value.messages,
        senders = aiContextSenders(),
        limit = 30
    )
}

internal fun ChatDetailViewModel.inferGroupAiMode(query: String): String {
    val normalized = query.trim().lowercase()
    return when {
        normalized.startsWith("总结") || normalized.startsWith("概括") || normalized.startsWith("summary") || normalized.startsWith("summarize") -> "summary"
        normalized.startsWith("决策") || normalized.startsWith("决定") || normalized.startsWith("decisions") -> "decisions"
        normalized.startsWith("待办") || normalized.startsWith("任务") || normalized.startsWith("tasks") || normalized.startsWith("todo") -> "tasks"
        normalized.startsWith("时间线") || normalized.startsWith("时间轴") || normalized.startsWith("timeline") || normalized.startsWith("chronology") -> "timeline"
        normalized.startsWith("风险") || normalized.startsWith("隐患") || normalized.startsWith("risk") || normalized.startsWith("blocker") -> "risks"
        else -> "answer"
    }
}

internal fun ChatDetailViewModel.parseGroupAiCommand(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("@AI", ignoreCase = true)) return null
    if (trimmed.length > 3 && !trimmed[3].isWhitespace()) return null
    return trimmed.drop(3).trim()
}
