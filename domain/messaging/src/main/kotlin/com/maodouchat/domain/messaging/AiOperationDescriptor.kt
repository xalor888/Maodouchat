package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.Serializable

@Serializable
data class AiOperationParameters(
    val targetLanguage: String? = null,
    val summaryScope: String? = null,
    val messageIds: List<String> = emptyList(),
    val analysisMode: String? = null,
    val question: String? = null
)

object AiOperationType {
    const val TRANSCRIBE_VOICE = "TRANSCRIBE_VOICE"
    const val TRANSLATE_MESSAGE = "TRANSLATE_MESSAGE"
    const val SUMMARIZE_MESSAGES = "SUMMARIZE_MESSAGES"
    const val ANALYZE_IMAGE = "ANALYZE_IMAGE"
    const val ANALYZE_FILE = "ANALYZE_FILE"
}

data class AiOperationDescriptor(
    val type: String,
    val targetMessageId: String?,
    val parameters: AiOperationParameters
)

fun describeAiOperation(action: PendingAiAction): AiOperationDescriptor? = when (action) {
    is PendingAiAction.TranscribeVoice -> AiOperationDescriptor(
        AiOperationType.TRANSCRIBE_VOICE,
        action.messageId,
        AiOperationParameters()
    )
    is PendingAiAction.TranslateMessage -> AiOperationDescriptor(
        AiOperationType.TRANSLATE_MESSAGE,
        action.messageId,
        AiOperationParameters(targetLanguage = action.targetLanguage.take(40))
    )
    is PendingAiAction.Summarize -> AiOperationDescriptor(
        AiOperationType.SUMMARIZE_MESSAGES,
        null,
        AiOperationParameters(
            summaryScope = action.scope.name,
            messageIds = action.messages.map(Message::id)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_AI_SUMMARY_MESSAGES)
        )
    )
    is PendingAiAction.AnalyzeImage -> AiOperationDescriptor(
        AiOperationType.ANALYZE_IMAGE,
        action.messageId,
        AiOperationParameters(analysisMode = action.mode.wireValue)
    )
    is PendingAiAction.AnalyzeFile -> AiOperationDescriptor(
        AiOperationType.ANALYZE_FILE,
        action.messageId,
        AiOperationParameters(
            analysisMode = action.mode.wireValue,
            question = action.question?.trim()?.take(500)
        )
    )
    else -> null
}

suspend fun restoreAiAction(
    type: String,
    targetMessageId: String?,
    parameters: AiOperationParameters,
    messageById: suspend (String) -> Message?
): PendingAiAction? = when (type) {
    AiOperationType.TRANSCRIBE_VOICE -> {
        val messageId = targetMessageId ?: return null
        messageById(messageId)?.takeIf { it.type == MessageType.VOICE }
            ?: return null
        PendingAiAction.TranscribeVoice(messageId)
    }
    AiOperationType.TRANSLATE_MESSAGE -> {
        val messageId = targetMessageId ?: return null
        messageById(messageId)?.takeIf { it.type == MessageType.TEXT }
            ?: return null
        val targetLanguage = parameters.targetLanguage?.takeIf(String::isNotBlank) ?: return null
        PendingAiAction.TranslateMessage(messageId, targetLanguage)
    }
    AiOperationType.SUMMARIZE_MESSAGES -> {
        val scope = parameters.summaryScope?.let { value ->
            AiSummaryScope.entries.firstOrNull { it.name == value }
        } ?: return null
        val messages = parameters.messageIds.mapNotNull { messageById(it) }
        if (messages.isEmpty()) return null
        PendingAiAction.Summarize(scope, messages)
    }
    AiOperationType.ANALYZE_IMAGE -> {
        val messageId = targetMessageId ?: return null
        messageById(messageId)?.takeIf { it.type == MessageType.IMAGE }
            ?: return null
        val mode = AiImageAnalysisMode.entries.firstOrNull {
            it.wireValue == parameters.analysisMode
        } ?: return null
        PendingAiAction.AnalyzeImage(messageId, mode)
    }
    AiOperationType.ANALYZE_FILE -> {
        val messageId = targetMessageId ?: return null
        messageById(messageId)?.takeIf { it.type == MessageType.FILE }
            ?: return null
        val mode = AiFileAnalysisMode.entries.firstOrNull {
            it.wireValue == parameters.analysisMode
        } ?: return null
        val question = parameters.question?.trim()?.takeIf(String::isNotBlank)
        if (mode == AiFileAnalysisMode.QUESTION && question.isNullOrBlank()) return null
        PendingAiAction.AnalyzeFile(messageId, mode, question)
    }
    else -> null
}
