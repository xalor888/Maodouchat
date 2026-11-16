package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiStatusStripPolicyTest {

    @Test
    fun `visible when working unless draft streaming`() {
        assertTrue(aiStatusStripVisible(isAiWorking = true, isAiDraftStreaming = false, aiSuggestions = emptyList(), aiReplyStreamErrorCode = null))
        assertFalse(aiStatusStripVisible(isAiWorking = true, isAiDraftStreaming = true, aiSuggestions = emptyList(), aiReplyStreamErrorCode = null))
    }

    @Test
    fun `visible when suggestions or error even if idle`() {
        assertTrue(aiStatusStripVisible(isAiWorking = false, isAiDraftStreaming = false, aiSuggestions = listOf("try this"), aiReplyStreamErrorCode = null))
        assertTrue(aiStatusStripVisible(isAiWorking = false, isAiDraftStreaming = false, aiSuggestions = emptyList(), aiReplyStreamErrorCode = "ERR_X"))
    }

    @Test
    fun `hidden when idle with no suggestions or error`() {
        assertFalse(aiStatusStripVisible(isAiWorking = false, isAiDraftStreaming = false, aiSuggestions = emptyList(), aiReplyStreamErrorCode = null))
    }

    @Test
    fun `mode priority streaming over working over suggestions over error`() {
        assertEquals(AiStatusStripMode.STREAMING, aiStatusStripMode(isAiReplyStreaming = true, isAiWorking = true, aiSuggestions = listOf("s")))
        assertEquals(AiStatusStripMode.WORKING, aiStatusStripMode(isAiReplyStreaming = false, isAiWorking = true, aiSuggestions = listOf("s")))
        assertEquals(AiStatusStripMode.SUGGESTIONS, aiStatusStripMode(isAiReplyStreaming = false, isAiWorking = false, aiSuggestions = listOf("s")))
        assertEquals(AiStatusStripMode.ERROR, aiStatusStripMode(isAiReplyStreaming = false, isAiWorking = false, aiSuggestions = emptyList()))
    }
}
