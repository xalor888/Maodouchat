package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.model.Message
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.ai.AiCostVisibilityPolicy
import com.maodouchat.ai.AiRetryPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal sealed class PendingAiAction {
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

internal fun PendingAiAction.invocationCategory(): AiRetryPolicy.Category = when (this) {
    is PendingAiAction.AnalyzeImage,
    is PendingAiAction.AnalyzeFile,
    is PendingAiAction.GroupAssistant -> AiRetryPolicy.Category.HEAVY
    else -> AiRetryPolicy.Category.LIGHT
}

internal sealed interface AiOperationPreparation {
    data object Untracked : AiOperationPreparation
    data object InvalidContext : AiOperationPreparation
    data class Persisted(val operation: AiOperationEntity) : AiOperationPreparation
}

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

    private data class Descriptor(
        val type: String,
        val targetMessageId: String?,
        val parameters: AiOperationParameters
    )
}

internal fun classifyAiOperationError(error: Throwable): String = when {
    error is ApiException &&
        error.kind == ApiFailureKind.NETWORK &&
        !error.requestMayHaveReachedServer -> AiOperationError.CONNECTION_NOT_ESTABLISHED
    error is ApiException && error.kind in setOf(
        ApiFailureKind.NETWORK,
        ApiFailureKind.TIMEOUT,
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNEXPECTED
    ) -> AiOperationError.OUTCOME_UNKNOWN
    error is ApiException && error.kind == ApiFailureKind.HTTP -> {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(
            statusCode = error.statusCode,
            serverCode = error.serverCode,
            serverMessage = error.serverMessage,
            serverRetryAfterSeconds = error.retryAfterSeconds
        )
        AiCostVisibilityPolicy.mapToErrorCode(signal)
            ?: AiOperationError.SERVER
    }
    else -> AiOperationError.UNKNOWN
}

/** 附带 retry-after 的错误码解析；[persistedCode] 可写入 Room lastErrorCode。 */
internal data class AiClassifiedError(
    val code: String,
    val retryAfterSeconds: Long? = null,
    val persistedCode: String = code
)

internal fun classifyAiOperationErrorDetailed(error: Throwable): AiClassifiedError {
    val code = classifyAiOperationError(error)
    val retry = (error as? ApiException)?.retryAfterSeconds?.takeIf { it > 0L }
        ?: if (code == AiOperationError.RATE_LIMITED || code == AiOperationError.QUOTA_EXCEEDED) {
            AiCostVisibilityPolicy.waitSecondsFor(code).takeIf { it > 0L }
        } else null
    return AiClassifiedError(
        code = code,
        retryAfterSeconds = retry,
        persistedCode = AiCostVisibilityPolicy.encodeErrorCode(code, retry)
    )
}
