package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import com.maodouchat.domain.messaging.ConversationPrivacyPolicy
import com.maodouchat.domain.messaging.PrivacyAction
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun ChatDetailViewModel.normalizeAiRewriteMode(mode: String?): String {
    val m = mode?.trim()?.lowercase().orEmpty()
    return when (m) {
        "polish", "shorten", "formal", "gentle", "casual",
        "professional", "expand", "bullet", "translate", "clarify" -> m
        else -> "polish"
    }
}

internal fun ChatDetailViewModel.isAiAllowed(): Boolean {
    val targetChatId = activeChatId
    val caps = if (targetChatId.isNotBlank()) {
        com.maodouchat.security.SecretChatCapabilities.forChat(targetChatId)
    } else {
        val isSecret = _uiState.value.isSecretChat == true || _uiState.value.chat?.isSecret == true
        ConversationPrivacyCapabilities(isSecretChat = isSecret, isLocked = false)
    }
    return ConversationPrivacyPolicy.allows(caps, PrivacyAction.AI)
}

fun ChatDetailViewModel.requestAiRewrite(mode: String, targetLanguage: String? = null) {
    if (_uiState.value.isAiWorking) return
    // 密聊会话禁止 AI 改写：解密明文不得送服务端 AI
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_rewrite_disabled)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    if (_uiState.value.inputText.trim().isBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_enter_draft)) }
        return
    }
    runAiWithConsent(PendingAiAction.Rewrite(normalizeAiRewriteMode(mode), targetLanguage))
}

fun ChatDetailViewModel.requestAiSuggestions(tone: String = "friendly") {
    if (_uiState.value.isAiWorking) return
    // 密聊会话禁止 AI 建议回复：解密明文不得送服务端 AI
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_suggest_replies_disabled)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    if (buildAiContextMessages(limit = 12).isEmpty()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_no_reply_context)) }
        return
    }
    val safeTone = when (tone.trim().lowercase()) {
        "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
        else -> "friendly"
    }
    lastAiReplyTone = safeTone
    runAiWithConsent(PendingAiAction.SuggestReplies(safeTone))
}

fun ChatDetailViewModel.requestAiSummary(
    scope: AiSummaryScope,
    searchResultIds: List<String> = emptyList(),
    style: String = "brief"
) {
    if (_uiState.value.isAiWorking) return
    // 密聊会话禁止 AI 聚合：解密明文不得送服务端 AI
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_summary_disabled)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val safeStyle = when (style.trim().lowercase()) {
        "brief", "detailed", "decisions", "tasks", "timeline", "risks" -> style.trim().lowercase()
        else -> "brief"
    }
    viewModelScope.launch {
        // 9.148：快照账号与目标会话，DB 读取后过门禁——换号后不得把旧会话消息
        // 送进新会话的 AI 汇总流（其余 AI 路径均已有快照+门禁）
        val summaryOwnerUserId = currentUserId
        val summaryChatId = activeChatId
        if (summaryOwnerUserId.isBlank() || summaryChatId.isBlank()) return@launch
        val cachedMessages = try {
            withContext(Dispatchers.IO) {
                messageRepo.getMessagesByChatId(summaryChatId).first()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            _uiState.value.messages
        }
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = summaryOwnerUserId,
        )
        ) {
            return@launch
        }
        val allDecryptedMessages = (cachedMessages + _uiState.value.messages)
            .associateBy(Message::id)
            .values
            .toList()
        val candidates = summaryCandidates(scope, searchResultIds, allDecryptedMessages)
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_no_summary_context)) }
            return@launch
        }
        runAiWithConsent(PendingAiAction.Summarize(scope, candidates, safeStyle))
    }
}

internal fun ChatDetailViewModel.requestGroupAiAssistant(query: String, mode: String? = null) {
    if (_uiState.value.chat?.isGroup != true) return
    if (_uiState.value.isAiWorking) return
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val normalizedQuery = query.trim().take(600)
    if (normalizedQuery.isBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_empty_query)) }
        return
    }
    if (buildGroupAiContextMessages().isEmpty()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_no_context)) }
        return
    }
    val safeMode = when (mode?.trim()?.lowercase()) {
        "answer", "summary", "decisions", "tasks", "timeline", "risks" -> mode.trim().lowercase()
        else -> inferGroupAiMode(normalizedQuery)
    }
    runAiWithConsent(PendingAiAction.GroupAssistant(normalizedQuery, safeMode))
}

fun ChatDetailViewModel.requestGroupAiWithMode(query: String, mode: String) {
    requestGroupAiAssistant(query, mode)
}

fun ChatDetailViewModel.requestVoiceTranscription(messageId: String) {
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    val isTranscribing = messageId in _uiState.value.transcribingVoiceMessageIds
    if (!com.maodouchat.util.VoiceTranscriptPolicy.canRequest(
            isVoiceMessage = message.type == MessageType.VOICE,
            transcript = message.parsedMeta().voiceTranscript,
            isTranscribing = isTranscribing
        )
    ) {
        if (com.maodouchat.util.VoiceTranscriptPolicy.hasTranscript(message.parsedMeta().voiceTranscript)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_transcript_exists)) }
        }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    runAiWithConsent(PendingAiAction.TranscribeVoice(messageId))
}

internal fun ChatDetailViewModel.maybeAutoTranslateIncoming(message: Message) {
    if (message.senderId == currentUserId) return
    if (message.type != MessageType.TEXT && message.type != MessageType.MARKDOWN) return
    if (!isAiAllowed()) return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) return
    if (!_uiState.value.aiEnabled) return
    val app = getApplication<Application>()
    if (!com.maodouchat.ai.AiPrivacyPreferences.autoTranslateIncoming(app)) return
    if (!com.maodouchat.ai.AiPrivacyPreferences.mayUploadCloudContext(app)) return
    val text = message.parsedContent().trim()
    if (text.isBlank()) return
    val target = when (com.maodouchat.util.AppLocaleManager.getMode(app)) {
        com.maodouchat.util.AppLocaleManager.MODE_ENGLISH -> "en"
        else -> "zh"
    }
    if (!message.parsedMeta().translations[target].isNullOrBlank()) return
    requestMessageTranslation(message.id, target)
}

fun ChatDetailViewModel.requestMessageTranslation(messageId: String, targetLanguage: String = DEFAULT_TRANSLATION_LANGUAGE) {
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    if (message.type != MessageType.TEXT && message.type != MessageType.MARKDOWN) return
    if (messageId in _uiState.value.translatingMessageIds) return
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val text = message.parsedContent().trim()
    if (text.isBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_no_translation_text)) }
        return
    }
    val currentMeta = message.parsedMeta()
    if (!currentMeta.translations[targetLanguage].isNullOrBlank()) {
        val updatedMeta = currentMeta.copy(preferredTranslationLanguage = targetLanguage)
        val updated = message.copy(content = composeContentWithMeta(message.parsedContent(), updatedMeta))
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) updated else it },
                groupEncryptionWarning = text(R.string.chat_translation_selected)
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            messageRepo.insertMessage(updated)
        }
        return
    }
    runAiWithConsent(PendingAiAction.TranslateMessage(messageId, targetLanguage))
}
