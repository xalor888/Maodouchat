package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAiSharePolicyTest {

    @Test
    fun `non group answer is blocked`() {
        val decision = GroupAiSharePolicy.decideShare(false, "hello")
        assertFalse(decision.allowed)
        assertEquals(BlockReason.NOT_GROUP, decision.reason)
    }

    @Test
    fun `blank answer is blocked`() {
        val decision = GroupAiSharePolicy.decideShare(true, "   ")
        assertFalse(decision.allowed)
        assertEquals(BlockReason.EMPTY_ANSWER, decision.reason)
    }

    @Test
    fun `long answer is truncated to max share chars`() {
        val decision = GroupAiSharePolicy.decideShare(true, "x".repeat(5000))
        assertTrue(decision.allowed)
        assertEquals(GroupAiSharePolicy.MAX_SHARE_CHARS, decision.body.length)
    }

    @Test
    fun `sanitize drops blank titles and caps count`() {
        val drafts = listOf(TaskDraft(title = "  ")) +
            List(40) { TaskDraft(title = "task $it") }
        val cleaned = GroupAiSharePolicy.sanitizeTasks(drafts)
        assertEquals(GroupAiSharePolicy.MAX_TASKS_PER_SAVE, cleaned.size)
        assertTrue(cleaned.all { it.title.isNotBlank() })
    }

    @Test
    fun `closed loop ready requires group answer and no system identity`() {
        assertTrue(GroupAiSharePolicy.isClosedLoopReady(true, "answer"))
        assertFalse(GroupAiSharePolicy.isClosedLoopReady(false, "answer"))
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                true, "answer", listOf(TaskDraft(title = "  "))
            )
        )
    }
}
