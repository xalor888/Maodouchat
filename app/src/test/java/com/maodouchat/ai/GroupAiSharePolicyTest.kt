package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAiSharePolicyTest {
    @Test
    fun `share requires group and non-blank answer`() {
        assertEquals(
            BlockReason.NOT_GROUP,
            GroupAiSharePolicy.decideShare(false, "hello").reason
        )
        assertEquals(
            BlockReason.EMPTY_ANSWER,
            GroupAiSharePolicy.decideShare(true, "  ").reason
        )
        assertEquals(
            BlockReason.ALREADY_SHARED,
            GroupAiSharePolicy.decideShare(true, "hello", alreadyShared = true).reason
        )
        val ok = GroupAiSharePolicy.decideShare(true, "  answer  ")
        assertTrue(ok.allowed)
        assertEquals("answer", ok.body)
    }

    @Test
    fun `share meta never claims system identity`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("tasks")
        assertEquals(true, meta["aiAssisted"])
        assertEquals("tasks", meta["aiAssistantMode"])
        assertEquals(false, meta["systemIdentity"])
    }

    @Test
    fun `sanitize tasks drops blanks and caps size`() {
        val drafts = List(25) { i ->
            TaskDraft(
                title = if (i == 0) "  " else " task $i ",
                owner = "  alice  ",
                dueAt = if (i == 2) 0L else 100L + i
            )
        }
        val cleaned = GroupAiSharePolicy.sanitizeTasks(drafts)
        assertEquals(24, cleaned.size)
        assertFalse(cleaned.any { it.title.isBlank() })
        assertEquals("task 1", cleaned.first().title)
        assertEquals("alice", cleaned.first().owner)
        assertNull(cleaned.firstOrNull { it.title == "task 2" }?.dueAt)
    }

    @Test
    fun `closed loop requires confirmable share and non-system identity`() {
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "summary for the group",
                tasks = listOf(TaskDraft(title = "follow up"))
            )
        )
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "summary",
                alreadyShared = true
            )
        )
        assertFalse(GroupAiSharePolicy.isClosedLoopReady(isGroup = false, answer = "x"))
        assertTrue(GroupAiSharePolicy.canPersistTasks(listOf(TaskDraft("a"))))
        assertFalse(GroupAiSharePolicy.canPersistTasks(listOf(TaskDraft("  "))))
    }
}
