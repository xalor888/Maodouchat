package com.maodouchat.chatdetail

import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.model.Message
import com.maodouchat.ui.screen.chatdetail.AiFileAnalysisMode
import com.maodouchat.ui.screen.chatdetail.AiOperationPreparation
import com.maodouchat.ui.screen.chatdetail.AiSummaryScope
import com.maodouchat.ui.screen.chatdetail.ChatAiOperationFactory
import com.maodouchat.ui.screen.chatdetail.PendingAiAction
import com.maodouchat.ui.screen.chatdetail.classifyAiOperationError
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.ui.screen.chatdetail.AiImageAnalysisMode
import com.maodouchat.ui.screen.chatdetail.invocationCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAiOperationModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stream-only actions do not create recovery records`() {
        assertEquals(
            AiOperationPreparation.Untracked,
            ChatAiOperationFactory.prepare(PendingAiAction.Rewrite("formal"), "", "")
        )
    }

    @Test
    fun `all ai actions have a shared light or heavy invocation category`() {
        assertEquals(AiRetryPolicy.Category.LIGHT, PendingAiAction.Rewrite("formal").invocationCategory())
        assertEquals(AiRetryPolicy.Category.LIGHT, PendingAiAction.SuggestReplies().invocationCategory())
        assertEquals(
            AiRetryPolicy.Category.LIGHT,
            PendingAiAction.SemanticSearch("query", listOf("m1")).invocationCategory()
        )
        assertEquals(
            AiRetryPolicy.Category.HEAVY,
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR).invocationCategory()
        )
        assertEquals(
            AiRetryPolicy.Category.HEAVY,
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null).invocationCategory()
        )
        assertEquals(
            AiRetryPolicy.Category.HEAVY,
            PendingAiAction.GroupAssistant("query", "answer").invocationCategory()
        )
    }

    @Test
    fun `recoverable action fails closed without account and chat context`() {
        val action = PendingAiAction.TranscribeVoice("message-1")
        assertEquals(AiOperationPreparation.InvalidContext, ChatAiOperationFactory.prepare(action, "", "chat-1"))
        assertEquals(AiOperationPreparation.InvalidContext, ChatAiOperationFactory.prepare(action, "user-1", ""))
    }

    @Test
    fun `persisted operation is account scoped and bounds parameters`() {
        val preparation = ChatAiOperationFactory.prepare(
            action = PendingAiAction.AnalyzeFile(
                messageId = "message-1",
                mode = AiFileAnalysisMode.QUESTION,
                question = "  ${"x".repeat(700)}  "
            ),
            ownerUserId = "user-1",
            chatId = "chat-1",
            now = 123L,
            operationId = "operation-1"
        ) as AiOperationPreparation.Persisted

        val operation = preparation.operation
        val parameters = json.decodeFromString(AiOperationParameters.serializer(), operation.parametersJson)
        assertEquals("operation-1", operation.id)
        assertEquals("user-1", operation.ownerUserId)
        assertEquals("chat-1", operation.chatId)
        assertEquals(AiOperationType.ANALYZE_FILE, operation.type)
        assertEquals("message-1", operation.targetMessageId)
        assertEquals(500, parameters.question?.length)
        assertEquals(123L, operation.createdAt)
        assertEquals(123L, operation.updatedAt)
    }

    @Test
    fun `summary stores at most thirty distinct nonblank message ids`() {
        val messages = buildList {
            add(Message(id = "", chatId = "chat-1", senderId = "u", content = "blank"))
            repeat(40) { index ->
                add(Message(id = "m$index", chatId = "chat-1", senderId = "u", content = "text"))
            }
            add(Message(id = "m0", chatId = "chat-1", senderId = "u", content = "duplicate"))
        }
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.RECENT, messages),
            ownerUserId = "user-1",
            chatId = "chat-1",
            operationId = "summary-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(40, parameters.messageIds.size)
        assertEquals(40, parameters.messageIds.distinct().size)
        assertTrue(parameters.messageIds.none(String::isBlank))
    }

    @Test
    fun `only proven connection failure is classified as safe for retry`() {
        assertEquals(
            AiOperationError.CONNECTION_NOT_ESTABLISHED,
            classifyAiOperationError(
                ApiException(ApiFailureKind.NETWORK, requestMayHaveReachedServer = false)
            )
        )
        listOf(
            ApiException(ApiFailureKind.NETWORK, requestMayHaveReachedServer = true),
            ApiException(ApiFailureKind.TIMEOUT),
            ApiException(ApiFailureKind.INVALID_RESPONSE),
            ApiException(ApiFailureKind.UNEXPECTED)
        ).forEach { error ->
            assertEquals(AiOperationError.OUTCOME_UNKNOWN, classifyAiOperationError(error))
        }
        assertEquals(
            AiOperationError.SERVER,
            classifyAiOperationError(ApiException(ApiFailureKind.HTTP, statusCode = 503))
        )
        assertEquals(AiOperationError.UNKNOWN, classifyAiOperationError(IllegalStateException("broken")))
    }

    @Test
    fun `http 429 and rate_limited codes classify as rate limited with wait`() {
        assertEquals(
            AiOperationError.RATE_LIMITED,
            classifyAiOperationError(
                ApiException(
                    ApiFailureKind.HTTP,
                    statusCode = 429,
                    serverMessage = "AI 请求过于频繁",
                    retryAfterSeconds = 40
                )
            )
        )
        assertEquals(
            AiOperationError.RATE_LIMITED,
            classifyAiOperationError(
                ApiException(
                    ApiFailureKind.HTTP,
                    statusCode = 503,
                    serverCode = "rate_limited"
                )
            )
        )
        assertEquals(
            AiOperationError.QUOTA_EXCEEDED,
            classifyAiOperationError(
                ApiException(
                    ApiFailureKind.HTTP,
                    statusCode = 403,
                    serverCode = "QUOTA_EXCEEDED"
                )
            )
        )
        val detailed = com.maodouchat.ui.screen.chatdetail.classifyAiOperationErrorDetailed(
            ApiException(
                ApiFailureKind.HTTP,
                statusCode = 429,
                serverMessage = "AI 请求过于频繁",
                retryAfterSeconds = 40
            )
        )
        assertEquals(AiOperationError.RATE_LIMITED, detailed.code)
        assertEquals(40L, detailed.retryAfterSeconds)
        assertEquals("RATE_LIMITED:40", detailed.persistedCode)
    }
}
