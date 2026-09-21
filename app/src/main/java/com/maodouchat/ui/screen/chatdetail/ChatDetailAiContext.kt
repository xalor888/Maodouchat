package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.AiContextMessage
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 未读消息的 AI 总结 + AI 上下文构建（G169 从 ChatDetailAiGeneration.kt 抽出，123 行）。
 *
 * `maybeGenerateUnreadSummary`：用户回到有未读消息的会话时，自动起一次总结，
 * 用 `unreadSummaryJob` 保证同一时刻只有一个在跑（晚到的取消早先的）。
 * `buildAiContextMessages` / `aiContextSenders`：把当前消息列表转成服务端 AI 上下文，
 * 并解析「我 / 对方 / 群成员」的显示名。
 *
 * 抽出理由：原文件 711 行，这两块与「请求 / 流式 / 取消 / 建议」无耦合。
 */
internal fun ChatDetailViewModel.maybeGenerateUnreadSummary(messages: List<Message>) {
    val state = _uiState.value
    val unreadCount = state.chat?.unreadCount ?: 0
    if (unreadCount <= 0 || messages.isEmpty() || token.isBlank()) return
    if (
        !aiSettingsLoaded ||
        !state.aiEnabled ||
        !com.maodouchat.ai.AiPrivacyPreferences.userEnabled(app) ||
        !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)
    ) return

    val candidates = messages
        .filter { message ->
            message.senderId != currentUserId &&
                (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) &&
                !message.parsedMeta().aiAssisted &&
                message.parsedContent().isNotBlank()
        }
        .sortedBy { it.timestamp }
        .takeLast(unreadCount.coerceIn(1, MAX_AI_SUMMARY_MESSAGES))

    if (candidates.isEmpty()) return
    val first = candidates.first()
    val last = candidates.last()
    val request = captureAiRequestSnapshot() ?: return
    val cacheKey = "${request.chatId}:${first.id}:${last.id}:${candidates.size}"
    // Only skip after a terminal success/definitive failure for this window.
    // Network blips must not permanently suppress auto unread summaries.
    if (unreadSummaryAttemptedForKey == cacheKey) return
    if (unreadSummaryInFlightKey == cacheKey || _uiState.value.isUnreadSummaryLoading) return

    val contextMessages = buildAiSummaryContextMessages(candidates)
    if (contextMessages.isEmpty()) return

    unreadSummaryInFlightKey = cacheKey
    val job = viewModelScope.launch {
        requireAiRequestCurrent(request)
        _uiState.update { it.copy(isUnreadSummaryLoading = true) }
        try {
            val cached = withContext(Dispatchers.IO) { aiSummaryRepo.getSummary(cacheKey) }
            requireAiRequestCurrent(request)
            if (cached != null) {
                unreadSummaryAttemptedForKey = cacheKey
                val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                    cached.summary,
                    text(R.string.chat_ai_privilege_hallucination_disclaimer)
                )
                _uiState.update {
                    it.copy(
                        unreadAiSummary = displaySummary,
                        unreadAiSummaryCount = cached.messageCount,
                        isUnreadSummaryLoading = false
                    )
                }
                return@launch
            }

            requireAiRequestCurrent(request)
            com.maodouchat.ai.agent.LocalAiGateway.summarize(
                getApplication(),
                contextMessages,
                "brief"
            ).fold(
                onSuccess = { generated ->
                    requireAiRequestCurrent(request)
                    unreadSummaryAttemptedForKey = cacheKey
                    val summary = generated.trim().take(3_000)
                    if (summary.isBlank()) {
                        _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                        return@fold
                    }
                    val saved = withContext(Dispatchers.IO) {
                        aiSummaryRepo.saveSummary(
                            cacheKey = cacheKey,
                            chatId = request.chatId,
                            startMessageId = first.id,
                            endMessageId = last.id,
                            messageCount = candidates.size,
                            summary = summary
                        )
                    }
                    requireAiRequestCurrent(request)
                    val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                        summary,
                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                    )
                    _uiState.update {
                        it.copy(
                            unreadAiSummary = displaySummary,
                            unreadAiSummaryCount = candidates.size,
                            isUnreadSummaryLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    requireAiRequestCurrent(request)
                    // Retryable transport errors leave the key unmarked so a later open can retry.
                    if (!isAmbiguousTransportFailure(error)) {
                        unreadSummaryAttemptedForKey = cacheKey
                    }
                    _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (isAiRequestCurrent(request)) {
                _uiState.update { it.copy(isUnreadSummaryLoading = false) }
            }
            throw error
        } catch (_: Throwable) {
            if (isAiRequestCurrent(request)) {
                _uiState.update { it.copy(isUnreadSummaryLoading = false) }
            }
        } finally {
            if (unreadSummaryInFlightKey == cacheKey) {
                unreadSummaryInFlightKey = null
            }
        }
    }
    unreadSummaryJob = job
    job.invokeOnCompletion {
        if (unreadSummaryJob === job) unreadSummaryJob = null
    }
}

internal fun ChatDetailViewModel.buildAiContextMessages(limit: Int): List<AiContextMessage> {
    return buildPlainAiContextMessages(
        messages = _uiState.value.messages,
        senders = aiContextSenders(),
        limit = limit
    )
}

internal fun ChatDetailViewModel.buildAiContextMessages(messages: List<Message>): List<AiContextMessage> {
    return buildPlainAiContextMessages(
        messages = messages,
        senders = aiContextSenders(),
        limit = messages.size.coerceAtLeast(1)
    )
}

internal fun ChatDetailViewModel.aiContextSenders(): AiContextSenders {
    val state = _uiState.value
    val fallback = if (state.chatIsGroup) {
        text(R.string.chat_other_person)
    } else {
        state.contact.displayName.ifBlank { text(R.string.chat_other_person) }
    }
    return AiContextSenders(
        currentUserId = currentUserId,
        currentUserLabel = text(R.string.chat_sender_me),
        fallbackLabel = fallback,
        namesByUserId = state.chat?.participants.orEmpty().associate { it.id to it.displayName }
    )
}
