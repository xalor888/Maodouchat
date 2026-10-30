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
import com.maodouchat.ui.screen.chatdetail.classifyAiOperationErrorDetailed
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.ai.AiRetryPolicyCategory
import com.maodouchat.ui.screen.chatdetail.AiImageAnalysisMode
import com.maodouchat.ui.screen.chatdetail.invocationCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(AiRetryPolicyCategory.LIGHT, PendingAiAction.Rewrite("formal").invocationCategory())
        assertEquals(AiRetryPolicyCategory.LIGHT, PendingAiAction.SuggestReplies().invocationCategory())
        assertEquals(
            AiRetryPolicyCategory.LIGHT,
            PendingAiAction.SemanticSearch("query", listOf("m1")).invocationCategory()
        )
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR).invocationCategory()
        )
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null).invocationCategory()
        )
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
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

    @Test
    fun `stream-only suggest replies and semantic search are untracked`() {
        assertEquals(
            AiOperationPreparation.Untracked,
            ChatAiOperationFactory.prepare(PendingAiAction.SuggestReplies("friendly"), "user-1", "chat-1")
        )
        assertEquals(
            AiOperationPreparation.Untracked,
            ChatAiOperationFactory.prepare(
                PendingAiAction.SemanticSearch("query", listOf("m1")),
                "user-1",
                "chat-1"
            )
        )
    }

    @Test
    fun `translate message clips target language to forty chars`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", "x".repeat(100)),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.TRANSLATE_MESSAGE, preparation.operation.type)
        assertEquals("x".repeat(40), parameters.targetLanguage)
    }

    @Test
    fun `transcribe voice persists with type and target message`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranscribeVoice("voice-1"),
            ownerUserId = "user-1",
            chatId = "chat-1",
            now = 42L
        ) as AiOperationPreparation.Persisted

        assertEquals(AiOperationType.TRANSCRIBE_VOICE, preparation.operation.type)
        assertEquals("voice-1", preparation.operation.targetMessageId)
        assertEquals(42L, preparation.operation.createdAt)
    }

    @Test
    fun `generated operation ids carry the aiop prefix`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranscribeVoice("voice-1"),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted

        assertTrue(preparation.operation.id.startsWith("aiop_"))
    }

    @Test
    fun `detailed server failure without retry hint carries no wait`() {
        val detailed = classifyAiOperationErrorDetailed(
            ApiException(ApiFailureKind.HTTP, statusCode = 503)
        )

        assertEquals(AiOperationError.SERVER, detailed.code)
        assertEquals(null, detailed.retryAfterSeconds)
    }

    @Test
    fun `analyze image persists wire mode and target`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.SAFETY),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.ANALYZE_IMAGE, preparation.operation.type)
        assertEquals("m1", preparation.operation.targetMessageId)
        assertEquals("safety", parameters.analysisMode)
    }

    @Test
    fun `analyze file trims question and keeps null`() {
        val withQuestion = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "  how  "),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val withParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            withQuestion.operation.parametersJson
        )
        assertEquals("how", withParams.question)

        val withoutQuestion = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val withoutParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            withoutQuestion.operation.parametersJson
        )
        assertNull(withoutParams.question)
    }

    @Test
    fun `summarize scope persists as enum name`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.TODAY, emptyList()),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals("TODAY", parameters.summaryScope)
        assertTrue(parameters.messageIds.isEmpty())
    }

    @Test
    fun `heavy image action fails closed without context`() {
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR), "", "chat-1"
            )
        )
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR), "user-1", ""
            )
        )
    }

    @Test
    fun `detailed quota failure without retry hint falls back to default wait`() {
        val detailed = classifyAiOperationErrorDetailed(
            ApiException(ApiFailureKind.HTTP, statusCode = 403, serverCode = "QUOTA_EXCEEDED")
        )

        assertEquals(AiOperationError.QUOTA_EXCEEDED, detailed.code)
        assertEquals(300L, detailed.retryAfterSeconds)
        assertEquals("QUOTA_EXCEEDED:300", detailed.persistedCode)
    }

    @Test
    fun `heavy group assistant action is untracked despite its category`() {
        assertEquals(
            AiRetryPolicyCategory.HEAVY,
            PendingAiAction.GroupAssistant("query", "answer").invocationCategory()
        )
        assertEquals(
            AiOperationPreparation.Untracked,
            ChatAiOperationFactory.prepare(
                PendingAiAction.GroupAssistant("query", "answer"), "user-1", "chat-1"
            )
        )
    }

    @Test
    fun `summarize caps message ids at forty-eight in order`() {
        val messages = (0 until 60).map { index ->
            Message(id = "m$index", chatId = "chat-1", senderId = "u", content = "text")
        }
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.RECENT, messages),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(48, parameters.messageIds.size)
        assertEquals((0 until 48).map { "m$it" }, parameters.messageIds)
    }

    @Test
    fun `translate message keeps surrounding spaces and only clips length`() {
        val spaced = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", "  English  "),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val spacedParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            spaced.operation.parametersJson
        )
        assertEquals("  English  ", spacedParams.targetLanguage)

        val long = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", "  " + "x".repeat(50)),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val longParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            long.operation.parametersJson
        )
        assertEquals("  " + "x".repeat(38), longParams.targetLanguage)
    }

    @Test
    fun `analyze file summarize persists the summarize wire mode`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.ANALYZE_FILE, preparation.operation.type)
        assertEquals("summarize", parameters.analysisMode)
        assertNull(parameters.question)
    }

    @Test
    fun `detailed non-retryable failures carry no wait and a bare persisted code`() {
        val connection = classifyAiOperationErrorDetailed(
            ApiException(ApiFailureKind.NETWORK, requestMayHaveReachedServer = false)
        )
        assertEquals(AiOperationError.CONNECTION_NOT_ESTABLISHED, connection.code)
        assertEquals(null, connection.retryAfterSeconds)
        assertEquals(AiOperationError.CONNECTION_NOT_ESTABLISHED, connection.persistedCode)

        val timeout = classifyAiOperationErrorDetailed(ApiException(ApiFailureKind.TIMEOUT))
        assertEquals(AiOperationError.OUTCOME_UNKNOWN, timeout.code)
        assertEquals(null, timeout.retryAfterSeconds)
        assertEquals(AiOperationError.OUTCOME_UNKNOWN, timeout.persistedCode)
    }
    @Test
    fun `analyze image ocr persists the ocr wire mode`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.ANALYZE_IMAGE, preparation.operation.type)
        assertEquals("m1", preparation.operation.targetMessageId)
        assertEquals("ocr", parameters.analysisMode)
    }

    @Test
    fun `summarize with only blank ids persists an empty id list`() {
        val messages = listOf(
            Message(id = "", chatId = "chat-1", senderId = "u", content = "blank"),
            Message(id = "   ", chatId = "chat-1", senderId = "u", content = "spaces")
        )
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.RECENT, messages),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.SUMMARIZE_MESSAGES, preparation.operation.type)
        assertTrue(parameters.messageIds.isEmpty())
    }

    @Test
    fun `analyze file blank question persists an empty string`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "   "),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals("", parameters.question)
    }

    @Test
    fun `summarize recent scope persists as enum name`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.RECENT, emptyList()),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals("RECENT", parameters.summaryScope)
        assertTrue(parameters.messageIds.isEmpty())
    }

    @Test
    fun `analyze file fails closed without owner or chat context`() {
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null), "", "chat-1"
            )
        )
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null), "user-1", ""
            )
        )
    }
}
