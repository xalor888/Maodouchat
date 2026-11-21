package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiOperationDescriptorTest {
    private fun message(id: String, type: MessageType = MessageType.TEXT) =
        Message(id = id, chatId = "chat-1", senderId = "u", content = "text", type = type)

    @Test
    fun `describe transcribe voice keeps type and target`() {
        val descriptor = describeAiOperation(PendingAiAction.TranscribeVoice("voice-1"))
        assertEquals(AiOperationType.TRANSCRIBE_VOICE, descriptor?.type)
        assertEquals("voice-1", descriptor?.targetMessageId)
        assertEquals(AiOperationParameters(), descriptor?.parameters)
    }

    @Test
    fun `describe translate clips target language to forty chars`() {
        val descriptor = describeAiOperation(PendingAiAction.TranslateMessage("m1", "x".repeat(100)))
        assertEquals(AiOperationType.TRANSLATE_MESSAGE, descriptor?.type)
        assertEquals("m1", descriptor?.targetMessageId)
        assertEquals("x".repeat(40), descriptor?.parameters?.targetLanguage)
    }

    @Test
    fun `describe summarize dedups blanks and caps at forty-eight`() {
        val messages = buildList {
            add(message(""))
            add(message("   "))
            repeat(50) { index -> add(message("m$index")) }
            add(message("m0"))
        }
        val descriptor = describeAiOperation(PendingAiAction.Summarize(AiSummaryScope.RECENT, messages))
        assertEquals(AiOperationType.SUMMARIZE_MESSAGES, descriptor?.type)
        assertNull(descriptor?.targetMessageId)
        assertEquals("RECENT", descriptor?.parameters?.summaryScope)
        assertEquals((0 until 48).map { "m$it" }, descriptor?.parameters?.messageIds)
    }

    @Test
    fun `describe analyze image keeps wire mode and target`() {
        val descriptor = describeAiOperation(PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.OCR))
        assertEquals(AiOperationType.ANALYZE_IMAGE, descriptor?.type)
        assertEquals("m1", descriptor?.targetMessageId)
        assertEquals("ocr", descriptor?.parameters?.analysisMode)
    }

    @Test
    fun `describe analyze file trims question to five hundred chars`() {
        val descriptor = describeAiOperation(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "  " + "x".repeat(600) + "  ")
        )
        assertEquals(AiOperationType.ANALYZE_FILE, descriptor?.type)
        assertEquals("question", descriptor?.parameters?.analysisMode)
        assertEquals("x".repeat(500), descriptor?.parameters?.question)

        val withoutQuestion = describeAiOperation(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null)
        )
        assertEquals("summarize", withoutQuestion?.parameters?.analysisMode)
        assertNull(withoutQuestion?.parameters?.question)
    }

    @Test
    fun `describe stream-only actions return null`() {
        assertNull(describeAiOperation(PendingAiAction.Rewrite("formal")))
        assertNull(describeAiOperation(PendingAiAction.SuggestReplies()))
        assertNull(describeAiOperation(PendingAiAction.SemanticSearch("query", listOf("m1"))))
        assertNull(describeAiOperation(PendingAiAction.GroupAssistant("query", "answer")))
    }

    @Test
    fun `restore transcribe voice returns action for a voice message`() = runBlocking {
        val byId = mapOf("v1" to message("v1", MessageType.VOICE))
        assertEquals(
            PendingAiAction.TranscribeVoice("v1"),
            restoreAiAction(AiOperationType.TRANSCRIBE_VOICE, "v1", AiOperationParameters()) { byId[it] }
        )
    }

    @Test
    fun `restore transcribe voice rejects missing id unknown message and wrong type`() = runBlocking {
        val byId = mapOf("v1" to message("v1", MessageType.VOICE))
        assertNull(restoreAiAction(AiOperationType.TRANSCRIBE_VOICE, null, AiOperationParameters()) { byId[it] })
        assertNull(restoreAiAction(AiOperationType.TRANSCRIBE_VOICE, "gone", AiOperationParameters()) { byId[it] })
        assertNull(restoreAiAction(AiOperationType.TRANSCRIBE_VOICE, "v1", AiOperationParameters()) { message("v1") })
    }

    @Test
    fun `restore translate returns action and rejects blank language`() = runBlocking {
        val byId = mapOf("m1" to message("m1"))
        assertEquals(
            PendingAiAction.TranslateMessage("m1", "en"),
            restoreAiAction(
                AiOperationType.TRANSLATE_MESSAGE, "m1",
                AiOperationParameters(targetLanguage = "en")
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.TRANSLATE_MESSAGE, "m1",
                AiOperationParameters(targetLanguage = "   ")
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.TRANSLATE_MESSAGE, "m1",
                AiOperationParameters(targetLanguage = null)
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.TRANSLATE_MESSAGE, "m1",
                AiOperationParameters(targetLanguage = "en")
            ) { message("m1", MessageType.VOICE) }
        )
    }

    @Test
    fun `restore summarize returns scoped action and rejects bad scope and empty list`() = runBlocking {
        val found = listOf(message("m1"), message("m2"))
        val byId = found.associateBy(Message::id)
        assertEquals(
            PendingAiAction.Summarize(AiSummaryScope.TODAY, found),
            restoreAiAction(
                AiOperationType.SUMMARIZE_MESSAGES, null,
                AiOperationParameters(summaryScope = "TODAY", messageIds = listOf("m1", "gone", "m2"))
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.SUMMARIZE_MESSAGES, null,
                AiOperationParameters(summaryScope = "NOPE", messageIds = listOf("m1"))
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.SUMMARIZE_MESSAGES, null,
                AiOperationParameters(summaryScope = "TODAY", messageIds = listOf("gone"))
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.SUMMARIZE_MESSAGES, null,
                AiOperationParameters(summaryScope = "TODAY", messageIds = emptyList())
            ) { byId[it] }
        )
    }

    @Test
    fun `restore analyze image returns mode and rejects unknown mode`() = runBlocking {
        val byId = mapOf("m1" to message("m1", MessageType.IMAGE))
        assertEquals(
            PendingAiAction.AnalyzeImage("m1", AiImageAnalysisMode.SAFETY),
            restoreAiAction(
                AiOperationType.ANALYZE_IMAGE, "m1",
                AiOperationParameters(analysisMode = "safety")
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.ANALYZE_IMAGE, "m1",
                AiOperationParameters(analysisMode = "nope")
            ) { byId[it] }
        )
    }

    @Test
    fun `restore analyze file requires a question only for question mode`() = runBlocking {
        val byId = mapOf("m1" to message("m1", MessageType.FILE))
        assertEquals(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "how?"),
            restoreAiAction(
                AiOperationType.ANALYZE_FILE, "m1",
                AiOperationParameters(analysisMode = "question", question = "  how?  ")
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.ANALYZE_FILE, "m1",
                AiOperationParameters(analysisMode = "question", question = "   ")
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.ANALYZE_FILE, "m1",
                AiOperationParameters(analysisMode = "question", question = null)
            ) { byId[it] }
        )
        assertEquals(
            PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null),
            restoreAiAction(
                AiOperationType.ANALYZE_FILE, "m1",
                AiOperationParameters(analysisMode = "summarize", question = null)
            ) { byId[it] }
        )
        assertNull(
            restoreAiAction(
                AiOperationType.ANALYZE_FILE, "m1",
                AiOperationParameters(analysisMode = "nope")
            ) { byId[it] }
        )
    }

    @Test
    fun `restore unknown type returns null`() = runBlocking {
        assertNull(
            restoreAiAction("SOMETHING_ELSE", "m1", AiOperationParameters()) { message("m1") }
        )
    }
}
