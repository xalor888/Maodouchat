package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ChatAiOperationModelsTest {

    @Test
    fun `suggest replies defaults to friendly tone`() {
        assertEquals("friendly", PendingAiAction.SuggestReplies().tone)
    }

    @Test
    fun `summarize defaults to brief style`() {
        val action = PendingAiAction.Summarize(AiSummaryScope.RECENT, listOf(Message(
            id = "m1", chatId = "c1", senderId = "u2", content = "",
            type = MessageType.TEXT, timestamp = 1L, status = MessageStatus.SENT,
        )))
        assertEquals("brief", action.style)
        assertEquals(AiSummaryScope.RECENT, action.scope)
    }

    @Test
    fun `rewrite keeps optional target language null by default`() {
        assertNull(PendingAiAction.Rewrite("formal").targetLanguage)
    }

    @Test
    fun `analyze file keeps optional question`() {
        val action = PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.QUESTION, "why?")
        assertEquals("why?", action.question)
        assertNull(PendingAiAction.AnalyzeFile("m1", AiFileAnalysisMode.SUMMARIZE, null).question)
    }
}
