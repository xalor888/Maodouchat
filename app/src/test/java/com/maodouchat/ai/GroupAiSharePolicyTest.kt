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

    @Test
    fun `decideShare truncates long answers to max`() {
        val decision = GroupAiSharePolicy.decideShare(true, "x".repeat(5000))
        assertTrue(decision.allowed)
        assertEquals(4_000, GroupAiSharePolicy.MAX_SHARE_CHARS)
        assertEquals(GroupAiSharePolicy.MAX_SHARE_CHARS, decision.body.length)
        assertNull(decision.reason)
    }

    @Test
    fun `blocked share carries empty body`() {
        val decision = GroupAiSharePolicy.decideShare(false, "hello")
        assertFalse(decision.allowed)
        assertEquals("", decision.body)
        assertEquals(BlockReason.NOT_GROUP, decision.reason)
        val empty = GroupAiSharePolicy.decideShare(true, null)
        assertFalse(empty.allowed)
        assertEquals(BlockReason.EMPTY_ANSWER, empty.reason)
    }

    @Test
    fun `shareAssistantMode trims and caps length`() {
        assertEquals("tasks", GroupAiSharePolicy.shareAssistantMode("  tasks  "))
        assertNull(GroupAiSharePolicy.shareAssistantMode("   "))
        assertNull(GroupAiSharePolicy.shareAssistantMode(null))
        assertEquals(40, GroupAiSharePolicy.shareAssistantMode("x".repeat(100))!!.length)
        assertTrue(GroupAiSharePolicy.shareAiAssistedFlag())
    }

    @Test
    fun `sanitizeTasks caps at 30 and drops invalid dueAt`() {
        val drafts = List(40) { i ->
            TaskDraft(title = " t$i ", dueAt = if (i % 2 == 0) -1L else i.toLong())
        }
        val cleaned = GroupAiSharePolicy.sanitizeTasks(drafts)
        assertEquals(GroupAiSharePolicy.MAX_TASKS_PER_SAVE, cleaned.size)
        assertEquals("t0", cleaned.first().title)
        assertNull(cleaned.first().dueAt)
        assertEquals(1L, cleaned[1].dueAt)
        val longTitle = GroupAiSharePolicy.sanitizeTasks(listOf(TaskDraft("x".repeat(500))))
        assertEquals(GroupAiSharePolicy.MAX_TASK_TITLE_CHARS, longTitle.single().title.length)
    }

    @Test
    fun `isClosedLoopReady needs persistable tasks and confirmable share`() {
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "summary",
                tasks = listOf(TaskDraft("  "))
            )
        )
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = null,
                tasks = listOf(TaskDraft("a"))
            )
        )
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = emptyList()
            )
        )
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                alreadyShared = true
            )
        )
    }
}
