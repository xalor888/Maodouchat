package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.Message
import com.maodouchat.network.AiContextMessage
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * AI 消息总结（G168 从 ChatDetailAiGeneration.kt 抽出，174 行）。
 *
 * `summaryCandidates` 按范围（最近 / 今天 / 7 天 / 30 天 / 搜索结果 / 未读）
 * 选出参与总结的消息，`buildAiSummaryContextMessages` 再把它们转成
 * 服务端 AI 上下文。已由 AI 生成的消息（`aiAssisted`）会被排除，避免总结套娃。
 *
 * 抽出理由：与「请求 / 流式 / 取消 / 建议」逻辑无耦合。
 */
internal fun ChatDetailViewModel.summarizeMessages(
    scope: AiSummaryScope,
    frozenMessages: List<Message>,
    operationId: String? = null,
    style: String = "brief"
) {
    val candidates = frozenMessages
        .sortedBy(Message::timestamp)
        .takeLast(MAX_AI_SUMMARY_MESSAGES)
    val contextMessages = buildAiSummaryContextMessages(candidates)
    if (contextMessages.isEmpty()) {
        launchTrackedAiOperation(operationId) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
        }
        return
    }
    val safeStyle = when (style.trim().lowercase()) {
        "brief", "detailed", "decisions", "tasks", "timeline", "risks" -> style.trim().lowercase()
        else -> if (scope == AiSummaryScope.RECENT) "brief" else "detailed"
    }
    val first = candidates.first()
    val last = candidates.last()
    val request = captureAiRequestSnapshot()
    if (request == null) {
        launchTrackedAiOperation(operationId) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
        }
        return
    }
    manualSummaryJob?.cancel()
    val generation = manualSummaryGate.next()
    val cacheKey = "manual:${request.chatId}:${scope.name}:$safeStyle:${first.id}:${last.id}:${contextMessages.size}"
    val job = launchTrackedAiOperation(operationId, startImmediately = false) {
        if (!manualSummaryGate.isCurrent(generation)) return@launchTrackedAiOperation
        requireAiRequestCurrent(request)
        _uiState.update {
            it.copy(
                isAiWorking = true,
                groupEncryptionWarning = null,
                aiSummary = null,
                aiSummaryScope = scope,
                aiSummaryMessageCount = contextMessages.size
            )
        }
        val cached = withContext(Dispatchers.IO) {
            aiSummaryRepo.getSummary(cacheKey)
        }
        if (!manualSummaryGate.isCurrent(generation)) return@launchTrackedAiOperation
        requireAiRequestCurrent(request)
        if (cached != null) {
            if (!completeAiOperation(operationId)) return@launchTrackedAiOperation
            val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                cached.summary,
                text(R.string.chat_ai_privilege_hallucination_disclaimer)
            )
            _uiState.update {
                it.copy(
                    aiSummary = displaySummary,
                    aiSummaryScope = scope,
                    aiSummaryMessageCount = cached.messageCount,
                    isAiWorking = false
                )
            }
            return@launchTrackedAiOperation
        }
        requireAiRequestCurrent(request)
        com.maodouchat.ai.agent.LocalAiGateway.summarize(
            getApplication(),
            contextMessages,
            safeStyle
        ).fold(
            onSuccess = { summary ->
                if (!manualSummaryGate.isCurrent(generation)) return@fold
                requireAiRequestCurrent(request)
                val generatedSummary = summary.take(3_000)
                if (generatedSummary.isBlank()) {
                    failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                    _uiState.update {
                        it.copy(
                            isAiWorking = false,
                            aiSummaryScope = null,
                            aiSummaryMessageCount = 0,
                            groupEncryptionWarning = text(R.string.chat_ai_summary_failed)
                        )
                    }
                    return@fold
                }
                val saved = withContext(Dispatchers.IO) {
                    aiSummaryRepo.saveSummary(
                        cacheKey = cacheKey,
                        chatId = request.chatId,
                        startMessageId = first.id,
                        endMessageId = last.id,
                        messageCount = contextMessages.size,
                        summary = generatedSummary
                    )
                }
                if (!manualSummaryGate.isCurrent(generation)) return@fold
                requireAiRequestCurrent(request)
                if (!completeAiOperation(operationId)) return@fold
                val displaySummary = com.maodouchat.ai.AiPromptSafetyPolicy
                    .annotateIfPrivilegedHallucination(
                        generatedSummary,
                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                    )
                _uiState.update {
                    it.copy(
                        aiSummary = displaySummary,
                        isAiWorking = false
                    )
                }
            },
            onFailure = { error ->
                if (!manualSummaryGate.isCurrent(generation)) return@fold
                requireAiRequestCurrent(request)
                failAiOperation(operationId, aiOperationErrorCode(error))
                _uiState.update {
                    it.copy(
                        isAiWorking = false,
                        aiSummaryScope = null,
                        aiSummaryMessageCount = 0,
                        groupEncryptionWarning = error.message ?: text(R.string.chat_ai_summary_failed)
                    )
                }
            }
        )
    }
    manualSummaryJob = job
    job.invokeOnCompletion {
        if (manualSummaryJob === job) manualSummaryJob = null
    }
    job.start()
}

internal fun ChatDetailViewModel.summaryCandidates(
    scope: AiSummaryScope,
    searchResultIds: List<String>,
    sourceMessages: List<Message>
): List<Message> {
    val now = System.currentTimeMillis()
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val searchIds = searchResultIds.filter(String::isNotBlank).toHashSet()
    return sourceMessages
        .asSequence()
        .filterNot { it.parsedMeta().aiAssisted }
        .filter { it.aiSummaryContextText().isNotBlank() }
        .filter { message ->
            when (scope) {
                AiSummaryScope.RECENT -> true
                AiSummaryScope.TODAY -> message.timestamp >= todayStart
                AiSummaryScope.SEVEN_DAYS -> message.timestamp >= now - SEVEN_DAYS_MS
                AiSummaryScope.THIRTY_DAYS -> message.timestamp >= now - THIRTY_DAYS_MS
                AiSummaryScope.SEARCH_RESULTS -> message.id in searchIds
                AiSummaryScope.UNREAD -> false
            }
        }
        .sortedBy(Message::timestamp)
        .toList()
        .takeLast(if (scope == AiSummaryScope.RECENT) 24 else MAX_AI_SUMMARY_MESSAGES)
}

internal fun ChatDetailViewModel.buildAiSummaryContextMessages(messages: List<Message>): List<AiContextMessage> {
    return buildSummaryAiContextMessages(
        messages = messages,
        senders = aiContextSenders(),
        limit = MAX_AI_SUMMARY_MESSAGES
    )
}
