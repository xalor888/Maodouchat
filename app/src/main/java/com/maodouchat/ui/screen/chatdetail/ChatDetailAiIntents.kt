package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import com.maodouchat.domain.messaging.ConversationPrivacyPolicy
import com.maodouchat.domain.messaging.PrivacyAction
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository
import com.maodouchat.attachment.AttachmentPreparationLease
import com.maodouchat.attachment.AttachmentSendCommand
import com.maodouchat.attachment.AttachmentSendWorkflow
import com.maodouchat.attachment.AttachmentSendWorkflowResult
import com.maodouchat.attachment.AttachmentDownloadCoordinator
import com.maodouchat.crypto.DecryptHistoryPolicy
import com.maodouchat.crypto.DecryptPlaceholderPolicy
import com.maodouchat.crypto.OwnSentMediaRestorePolicy
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.conversation.ConversationLocalCleanupStep
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator
import com.maodouchat.messaging.v2.MessagingV2EventOutbox
import com.maodouchat.messaging.v2.MessagingV2MutationFacade
import com.maodouchat.messaging.v2.ConversationMessageMutationCoordinator
import com.maodouchat.messaging.v2.ConversationMessageMutationOutcome
import com.maodouchat.messaging.v2.ConversationReactionCoordinator
import com.maodouchat.messaging.v2.ConversationReactionOutcome
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.messaging.v2.MessageMutationKind
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.messaging.v2.OutgoingConversationErrors
import com.maodouchat.messaging.v2.OutgoingConversationRequest
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import com.maodouchat.forwarding.ConversationForwardCoordinator
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.semanticSearchText
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiGroupTask
import com.maodouchat.network.AiSemanticSearchCandidate
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.PinnedMessageDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.scheduling.ChatReminderOutcome
import com.maodouchat.scheduling.ChatScheduleCommand
import com.maodouchat.scheduling.ChatScheduleController
import com.maodouchat.scheduling.ChatScheduleImmediateOutcome
import com.maodouchat.scheduling.ChatScheduleMutationOutcome
import com.maodouchat.scheduling.ChatScheduleRejection
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import com.maodouchat.scheduling.MessageReminderRequest
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure
import com.maodouchat.util.VoiceCapturePolicy
import com.maodouchat.util.VoicePlayer
import com.maodouchat.util.VoiceRecorder
import com.maodouchat.util.VoiceRecordingWaveform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID


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
        getApplication<MaodouchatApp>().secretConversationController.capabilities(targetChatId)
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

fun ChatDetailViewModel.requestAiImageAnalysis(messageId: String, mode: AiImageAnalysisMode) {
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    if (message.type != MessageType.IMAGE || _uiState.value.isAiWorking) return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_image_disabled)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val cached = message.parsedMeta().aiImageAnalyses[mode.wireValue]?.trim()?.takeIf(String::isNotBlank)
    if (cached != null) {
        val updatedMeta = message.parsedMeta().copy(preferredImageAnalysisMode = mode.wireValue)
        val updated = message.copy(
            content = composeContentWithMeta(message.parsedContent(), updatedMeta),
            meta = updatedMeta
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) updated else it },
                aiImageAnalysisResult = cached,
                aiImageAnalysisMode = mode
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            messageRepo.insertMessage(updated)
        }
        return
    }
    runAiWithConsent(PendingAiAction.AnalyzeImage(messageId, mode))
}

fun ChatDetailViewModel.clearAiImageAnalysis() {
    _uiState.update { it.copy(aiImageAnalysisResult = null, aiImageAnalysisMode = null) }
}

fun ChatDetailViewModel.requestAiFileAnalysis(messageId: String, mode: AiFileAnalysisMode, question: String? = null) {
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_file_disabled)) }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    if (message.type != MessageType.FILE || _uiState.value.isAiWorking) return
    val normalizedQuestion = question?.trim()?.take(500)
    if (mode == AiFileAnalysisMode.QUESTION && normalizedQuestion.isNullOrBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_file_question_empty)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val analysisKey = if (mode == AiFileAnalysisMode.QUESTION) {
        "question:" + (normalizedQuestion?.take(120) ?: "default")
    } else {
        mode.wireValue
    }
    val cached = message.parsedMeta().aiFileAnalyses[analysisKey]?.trim()?.takeIf(String::isNotBlank)
    if (cached != null) {
        val updatedMeta = message.parsedMeta().copy(
            preferredFileAnalysisMode = mode.wireValue,
            aiFileLastQuestion = if (mode == AiFileAnalysisMode.QUESTION) normalizedQuestion else message.parsedMeta().aiFileLastQuestion
        )
        val updated = message.copy(
            content = composeContentWithMeta(message.parsedContent(), updatedMeta),
            meta = updatedMeta
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) updated else it },
                aiFileAnalysisResult = cached,
                aiFileAnalysisMode = mode,
                aiFileAnalysisName = message.parsedMeta().fileName
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            messageRepo.insertMessage(updated)
        }
        return
    }
    runAiWithConsent(PendingAiAction.AnalyzeFile(messageId, mode, normalizedQuestion))
}

fun ChatDetailViewModel.clearAiFileAnalysis() {
    _uiState.update {
        it.copy(aiFileAnalysisResult = null, aiFileAnalysisMode = null, aiFileAnalysisName = null)
    }
}