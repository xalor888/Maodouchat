package com.maodouchat.chatdetail

import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.ui.screen.chatdetail.AiOperationPreparation
import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.ui.screen.chatdetail.ChatAiOperationFactory
import com.maodouchat.core.model.PendingAiAction
import com.maodouchat.core.model.AiRetryPolicyCategory
import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.domain.messaging.invocationCategory
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

    @Test
    fun `analyze image describe persists the describe wire mode`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.DESCRIBE),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.ANALYZE_IMAGE, preparation.operation.type)
        assertEquals("m1", preparation.operation.targetMessageId)
        assertEquals("describe", parameters.analysisMode)
    }

    @Test
    fun `rewrite with a target language stays untracked`() {
        assertEquals(
            AiOperationPreparation.Untracked,
            ChatAiOperationFactory.prepare(
                PendingAiAction.Rewrite("formal", "English"), "user-1", "chat-1"
            )
        )
    }

    @Test
    fun `summarize keeps forty-eight ids and drops the forty-ninth`() {
        val messages = (0 until 49).map { index ->
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

        val exact = (0 until 48).map { index ->
            Message(id = "m$index", chatId = "chat-1", senderId = "u", content = "text")
        }
        val exactPreparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.RECENT, exact),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val exactParameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            exactPreparation.operation.parametersJson
        )

        assertEquals(48, exactParameters.messageIds.size)
        assertEquals((0 until 48).map { "m$it" }, exactParameters.messageIds)
    }

    @Test
    fun `translate message keeps exactly forty chars and clips the forty-first`() {
        val exact = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", "x".repeat(40)),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val exactParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            exact.operation.parametersJson
        )
        assertEquals("x".repeat(40), exactParams.targetLanguage)

        val over = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", "x".repeat(41)),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val overParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            over.operation.parametersJson
        )
        assertEquals("x".repeat(40), overParams.targetLanguage)
    }

    @Test
    fun `analyze file question keeps exactly five hundred chars after trimming`() {
        val exact = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "  " + "x".repeat(500) + "  "),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val exactParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            exact.operation.parametersJson
        )
        assertEquals("x".repeat(500), exactParams.question)

        val over = ChatAiOperationFactory.prepare(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "x".repeat(501)),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val overParams = json.decodeFromString(
            AiOperationParameters.serializer(),
            over.operation.parametersJson
        )
        assertEquals("x".repeat(500), overParams.question)
    }

    @Test
    fun `translate with blank owner fails closed`() {
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.TranslateMessage("message-1", "en"),
                ownerUserId = "",
                chatId = "chat-1"
            )
        )
    }

    @Test
    fun `explicit operation id persists instead of a generated one`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranscribeVoice("voice-1"),
            ownerUserId = "user-1",
            chatId = "chat-1",
            operationId = "op-1"
        ) as AiOperationPreparation.Persisted

        assertEquals("op-1", preparation.operation.id)
    }

    @Test
    fun `summarize unread scope persists as enum name`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.UNREAD, emptyList()),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals("UNREAD", parameters.summaryScope)
        assertTrue(parameters.messageIds.isEmpty())
    }

    @Test
    fun `summarize keeps first occurrence order with interleaved duplicates`() {
        val messages = listOf("m2", "m1", "m2", "m0").map { id ->
            Message(id = id, chatId = "chat-1", senderId = "u", content = "text")
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

        assertEquals(listOf("m2", "m1", "m0"), parameters.messageIds)
    }

    @Test
    fun `translate with blank target language persists an empty string`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", ""),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals("", parameters.targetLanguage)
    }

    @Test
    fun `whitespace-only owner and chat fail closed for a recoverable action`() {
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.TranslateMessage("message-1", "en"),
                ownerUserId = "   ",
                chatId = "chat-1"
            )
        )
        assertEquals(
            AiOperationPreparation.InvalidContext,
            ChatAiOperationFactory.prepare(
                PendingAiAction.TranslateMessage("message-1", "en"),
                ownerUserId = "user-1",
                chatId = "   "
            )
        )
    }

    @Test
    fun `summarize mixed blank and whitespace ids keeps valid ids in order`() {
        val messages = listOf(" ", "m1", "", "m2", "m1").map { id ->
            Message(id = id, chatId = "chat-1", senderId = "u", content = "text")
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

        assertEquals(AiOperationType.SUMMARIZE_MESSAGES, preparation.operation.type)
        assertEquals(listOf("m1", "m2"), parameters.messageIds)
    }

    @Test
    fun `summarize target message id is null`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.Summarize(AiSummaryScope.RECENT, emptyList()),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted

        assertNull(preparation.operation.targetMessageId)
    }

    @Test
    fun `transcribe voice parameters are all empty defaults`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranscribeVoice("voice-1"),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.TRANSCRIBE_VOICE, preparation.operation.type)
        assertEquals("voice-1", preparation.operation.targetMessageId)
        assertNull(parameters.targetLanguage)
        assertNull(parameters.summaryScope)
        assertTrue(parameters.messageIds.isEmpty())
        assertNull(parameters.analysisMode)
        assertNull(parameters.question)
    }

    @Test
    fun `translate message persists its target message id`() {
        val preparation = ChatAiOperationFactory.prepare(
            PendingAiAction.TranslateMessage("message-1", "en"),
            ownerUserId = "user-1",
            chatId = "chat-1"
        ) as AiOperationPreparation.Persisted
        val parameters = json.decodeFromString(
            AiOperationParameters.serializer(),
            preparation.operation.parametersJson
        )

        assertEquals(AiOperationType.TRANSLATE_MESSAGE, preparation.operation.type)
        assertEquals("message-1", preparation.operation.targetMessageId)
        assertEquals("en", parameters.targetLanguage)
    }
}
