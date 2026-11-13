package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.PendingAiAction
import com.maodouchat.core.model.MAX_AI_SUMMARY_MESSAGES
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal object ChatAiOperationFactory {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun prepare(
        action: PendingAiAction,
        ownerUserId: String,
        chatId: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = "aiop_${UUID.randomUUID()}"
    ): AiOperationPreparation {
        val descriptor = descriptor(action) ?: return AiOperationPreparation.Untracked
        if (ownerUserId.isBlank() || chatId.isBlank()) return AiOperationPreparation.InvalidContext
        return AiOperationPreparation.Persisted(
            AiOperationEntity(
                id = operationId,
                ownerUserId = ownerUserId,
                chatId = chatId,
                type = descriptor.type,
                targetMessageId = descriptor.targetMessageId,
                parametersJson = json.encodeToString(descriptor.parameters),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun descriptor(action: PendingAiAction): Descriptor? = when (action) {
        is PendingAiAction.TranscribeVoice -> Descriptor(
            AiOperationType.TRANSCRIBE_VOICE,
            action.messageId,
            AiOperationParameters()
        )
        is PendingAiAction.TranslateMessage -> Descriptor(
            AiOperationType.TRANSLATE_MESSAGE,
            action.messageId,
            AiOperationParameters(targetLanguage = action.targetLanguage.take(40))
        )
        is PendingAiAction.Summarize -> Descriptor(
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
        is PendingAiAction.AnalyzeImage -> Descriptor(
            AiOperationType.ANALYZE_IMAGE,
            action.messageId,
            AiOperationParameters(analysisMode = action.mode.wireValue)
        )
        is PendingAiAction.AnalyzeFile -> Descriptor(
            AiOperationType.ANALYZE_FILE,
            action.messageId,
            AiOperationParameters(
                analysisMode = action.mode.wireValue,
                question = action.question?.trim()?.take(500)
            )
        )
        else -> null
    }

}
