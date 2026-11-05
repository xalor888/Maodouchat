package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLocalStateModelsTest {

    @Test
    fun `cleanup modes and steps cover the full destructive sweep`() {
        assertEquals(3, ConversationLocalCleanupMode.entries.size)
        assertEquals(26, ConversationLocalCleanupStep.entries.size)
        assertTrue(ConversationLocalCleanupStep.entries.contains(
            ConversationLocalCleanupStep.DELETE_CONVERSATION))
    }

    @Test
    fun `cleanup report completes only without session change and tracks failures`() {
        val failure = ConversationLocalCleanupFailure(
            ConversationLocalCleanupStep.DELETE_MESSAGES,
            IllegalStateException("boom"),
        )
        val report = ConversationLocalCleanupReport(
            sessionChanged = false,
            failures = listOf(failure),
        )
        assertTrue(report.completed)
        assertTrue(report.failed(ConversationLocalCleanupStep.DELETE_MESSAGES))
        assertFalse(report.failed(ConversationLocalCleanupStep.DELETE_DRAFT))
        assertFalse(report.copy(sessionChanged = true).completed)
    }
}
