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

    @Test
    fun `decideShare check order is group first then already-shared`() {
        assertEquals(
            BlockReason.NOT_GROUP,
            GroupAiSharePolicy.decideShare(false, "x", alreadyShared = true).reason
        )
        assertEquals(
            BlockReason.ALREADY_SHARED,
            GroupAiSharePolicy.decideShare(true, "  ", alreadyShared = true).reason
        )
        assertEquals(
            BlockReason.ALREADY_SHARED,
            GroupAiSharePolicy.decideShare(true, null, alreadyShared = true).reason
        )
        val answer = "x".repeat(4_000)
        val decision = GroupAiSharePolicy.decideShare(true, answer)
        assertTrue(decision.allowed)
        assertEquals(answer, decision.body)
    }

    @Test
    fun `shareAssistantMode passes short values through and nulls empties`() {
        assertNull(GroupAiSharePolicy.shareAssistantMode(""))
        assertNull(GroupAiSharePolicy.shareAssistantMode("   "))
        assertEquals("x".repeat(39), GroupAiSharePolicy.shareAssistantMode("x".repeat(39)))
        assertEquals("a b", GroupAiSharePolicy.shareAssistantMode("  a b  "))
    }

    @Test
    fun `share meta with null mode keeps identity contract`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta(null)
        assertEquals(true, meta["aiAssisted"])
        assertNull(meta["aiAssistantMode"])
        assertEquals(false, meta["systemIdentity"])
    }

    @Test
    fun `sanitizeTasks normalizes owner and dueText fields`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(
                TaskDraft(title = " t ", owner = "   ", dueText = "  soon  "),
                TaskDraft(title = "t2", owner = "x".repeat(150), dueText = "y".repeat(200)),
                TaskDraft(title = " t3 ", dueText = "")
            )
        )
        assertEquals(3, cleaned.size)
        assertNull(cleaned[0].owner)
        assertEquals("soon", cleaned[0].dueText)
        assertEquals(100, cleaned[1].owner!!.length)
        assertEquals(120, cleaned[1].dueText!!.length)
        assertNull(cleaned[2].dueText)
        assertNull(cleaned[2].owner)
        assertNull(cleaned[2].dueAt)
    }

    @Test
    fun `share meta has exactly the identity contract keys`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("tasks")
        assertEquals(setOf("aiAssisted", "aiAssistantMode", "systemIdentity"), meta.keys)
    }

    @Test
    fun `sanitizeTasks drops invalid titles before applying the cap`() {
        val drafts = List(35) { i ->
            TaskDraft(title = if (i < 10) "   " else " t$i ")
        }
        val cleaned = GroupAiSharePolicy.sanitizeTasks(drafts)
        assertEquals(25, cleaned.size)
        assertEquals("t10", cleaned.first().title)
    }

    @Test
    fun `title trim runs before the char cap`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "  " + "x".repeat(360)))
        )
        assertEquals("x".repeat(360), cleaned.single().title)
    }

    @Test
    fun `decideShare trims before truncating to max`() {
        val decision = GroupAiSharePolicy.decideShare(true, "  " + "x".repeat(4000) + "  ")
        assertTrue(decision.allowed)
        assertEquals("x".repeat(4000), decision.body)
    }

    @Test
    fun `share decisions and meta are stable across calls`() {
        assertEquals(
            GroupAiSharePolicy.decideShare(true, " answer "),
            GroupAiSharePolicy.decideShare(true, "  answer  ")
        )
        assertEquals(
            GroupAiSharePolicy.shareAsCurrentUserMeta("tasks"),
            GroupAiSharePolicy.shareAsCurrentUserMeta("  tasks  ")
        )
        val blocked = GroupAiSharePolicy.decideShare(false, "x")
        assertEquals(blocked, GroupAiSharePolicy.decideShare(false, "x"))
        assertEquals(BlockReason.NOT_GROUP, blocked.reason)
    }

    @Test
    fun `canPersistTasks rejects empty list and sanitize keeps order`() {
        assertFalse(GroupAiSharePolicy.canPersistTasks(emptyList()))
        assertTrue(
            GroupAiSharePolicy.canPersistTasks(
                listOf(TaskDraft(""), TaskDraft("ok"))
            )
        )
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft("b"), TaskDraft(""), TaskDraft("a"))
        )
        assertEquals(listOf("b", "a"), cleaned.map { it.title })
    }

    @Test
    fun `shareAssistantMode exact 40 boundary passes through`() {
        assertEquals("x".repeat(40), GroupAiSharePolicy.shareAssistantMode("x".repeat(40)))
    }

    @Test
    fun `sanitize caps keep exact lengths cut one over`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(
                TaskDraft(title = "x".repeat(361), owner = "y".repeat(100)),
                TaskDraft(title = "ok", owner = "y".repeat(101))
            )
        )
        assertEquals(360, cleaned[0].title.length)
        assertEquals(100, cleaned[0].owner!!.length)
        assertEquals(100, cleaned[1].owner!!.length)
    }

    @Test
    fun `sanitize dueText exact 120 passes through`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueText = "z".repeat(120)))
        )
        assertEquals(120, cleaned.single().dueText!!.length)
    }

    @Test
    fun `decideShare keeps internal newlines trims only ends`() {
        val decision = GroupAiSharePolicy.decideShare(true, "  a\nb  ")
        assertTrue(decision.allowed)
        assertEquals("a\nb", decision.body)
    }

    @Test
    fun `share meta blank mode nulls mode keeps keys`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("   ")
        assertEquals(true, meta["aiAssisted"])
        assertNull(meta["aiAssistantMode"])
        assertEquals(false, meta["systemIdentity"])
        assertEquals(setOf("aiAssisted", "aiAssistantMode", "systemIdentity"), meta.keys)
    }

    @Test
    fun `decideShare newline only answer is empty`() {
        assertEquals(
            BlockReason.EMPTY_ANSWER,
            GroupAiSharePolicy.decideShare(true, "\n\t").reason
        )
        val ok = GroupAiSharePolicy.decideShare(true, "\nhello\n")
        assertTrue(ok.allowed)
        assertEquals("hello", ok.body)
    }

    @Test
    fun `shareAssistantMode trims before capping and keeps inner spaces`() {
        assertEquals("x".repeat(40), GroupAiSharePolicy.shareAssistantMode("  " + "x".repeat(50)))
        assertEquals("a  b", GroupAiSharePolicy.shareAssistantMode("  a  b  "))
    }

    @Test
    fun `sanitize cap applies after dropping blanks`() {
        // 31 条里首条空白：先过滤再取 30，恰好留满 30 条；先取 30 再过滤只剩 29 条。
        val drafts = List(31) { i ->
            TaskDraft(title = if (i == 0) "   " else " t$i ")
        }
        val cleaned = GroupAiSharePolicy.sanitizeTasks(drafts)
        assertEquals(30, cleaned.size)
        assertEquals("t1", cleaned.first().title)
        assertEquals("t30", cleaned.last().title)
    }

    @Test
    fun `closed loop stays ready at and beyond max share chars`() {
        assertTrue(GroupAiSharePolicy.isClosedLoopReady(isGroup = true, answer = "x".repeat(4000)))
        assertTrue(GroupAiSharePolicy.isClosedLoopReady(isGroup = true, answer = "x".repeat(4001)))
    }

    @Test
    fun `decideShare keeps internal tabs trims only ends`() {
        val decision = GroupAiSharePolicy.decideShare(true, "  a\tb  ")
        assertTrue(decision.allowed)
        assertEquals("a\tb", decision.body)
    }

    @Test
    fun `decideShare truncates one char over max to exactly max`() {
        val decision = GroupAiSharePolicy.decideShare(true, "x".repeat(4001))
        assertTrue(decision.allowed)
        assertEquals("x".repeat(4000), decision.body)
        assertEquals(4000, decision.body.length)
    }

    @Test
    fun `decideShare rejects whitespace only answer even at max length`() {
        val decision = GroupAiSharePolicy.decideShare(true, " ".repeat(4000))
        assertFalse(decision.allowed)
        assertEquals(BlockReason.EMPTY_ANSWER, decision.reason)
    }

    @Test
    fun `sanitize dueText one over cap truncates to 120`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueText = "z".repeat(121)))
        )
        assertEquals(120, cleaned.single().dueText!!.length)
    }

    @Test
    fun `sanitize keeps max long dueAt`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueAt = Long.MAX_VALUE))
        )
        assertEquals(Long.MAX_VALUE, cleaned.single().dueAt)
    }

    @Test
    fun `share meta caps long mode to 40 chars`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("m".repeat(100))
        assertEquals("m".repeat(40), meta["aiAssistantMode"])
        assertEquals(true, meta["aiAssisted"])
        assertEquals(false, meta["systemIdentity"])
    }

    @Test
    fun `decideShare group check beats empty answer`() {
        val blank = GroupAiSharePolicy.decideShare(false, "  ")
        assertFalse(blank.allowed)
        assertEquals("", blank.body)
        assertEquals(BlockReason.NOT_GROUP, blank.reason)
        val nullAnswer = GroupAiSharePolicy.decideShare(false, null)
        assertFalse(nullAnswer.allowed)
        assertEquals(BlockReason.NOT_GROUP, nullAnswer.reason)
    }

    @Test
    fun `closed loop with already shared answer is not ready even with valid tasks`() {
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("a")),
                alreadyShared = true
            )
        )
    }

    @Test
    fun `closed loop with mixed blank and valid tasks is ready`() {
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("  "), TaskDraft("a"))
            )
        )
    }

    @Test
    fun `decideShare one under max keeps full body`() {
        val answer = "x".repeat(3999)
        val decision = GroupAiSharePolicy.decideShare(true, answer)
        assertTrue(decision.allowed)
        assertEquals(answer, decision.body)
        assertEquals(3999, decision.body.length)
    }

    @Test
    fun `canPersistTasks rejects all blank titles`() {
        assertFalse(GroupAiSharePolicy.canPersistTasks(listOf(TaskDraft("  "), TaskDraft("\n"))))
        assertFalse(GroupAiSharePolicy.canPersistTasks(listOf(TaskDraft(""))))
    }

    @Test
    fun `decideShare plain answer carries null reason and exact body`() {
        val decision = GroupAiSharePolicy.decideShare(true, "hello")
        assertTrue(decision.allowed)
        assertNull(decision.reason)
        assertEquals("hello", decision.body)
        val padded = GroupAiSharePolicy.decideShare(true, "  hello  ")
        assertEquals(decision, padded)
    }

    @Test
    fun `sanitizeTasks normalizes a full task object`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = " t ", owner = " o ", dueText = " d ", dueAt = 5L))
        )
        assertEquals(
            listOf(TaskDraft(title = "t", owner = "o", dueText = "d", dueAt = 5L)),
            cleaned
        )
        val blanked = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", owner = "  ", dueText = "", dueAt = 0L))
        )
        assertEquals(listOf(TaskDraft(title = "t")), blanked)
    }

    @Test
    fun `shareAssistantMode keeps inner newline and trims ends`() {
        assertEquals("a\nb", GroupAiSharePolicy.shareAssistantMode("  a\nb  "))
        assertEquals("a\tb", GroupAiSharePolicy.shareAssistantMode("\ta\tb\n"))
    }

    @Test
    fun `closed loop not ready for whitespace-only answer`() {
        assertFalse(GroupAiSharePolicy.isClosedLoopReady(isGroup = true, answer = "   "))
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "\n\t",
                tasks = listOf(TaskDraft("a"))
            )
        )
    }

    @Test
    fun `sanitizeTasks empty input stays empty`() {
        assertEquals(emptyList<TaskDraft>(), GroupAiSharePolicy.sanitizeTasks(emptyList()))
        assertTrue(GroupAiSharePolicy.sanitizeTasks(listOf(TaskDraft("  "))).isEmpty())
    }

    @Test
    fun `decideShare trims nbsp at ends and nbsp-only answer is blocked`() {
        // 实测 Kotlin trim 也去掉不换行空格：两端 NBSP 被裁掉，纯 NBSP 成空串被拦。
        val padded = GroupAiSharePolicy.decideShare(true, "\u00a0hello\u00a0")
        assertTrue(padded.allowed)
        assertEquals("hello", padded.body)
        val only = GroupAiSharePolicy.decideShare(true, "\u00a0")
        assertFalse(only.allowed)
        assertEquals(BlockReason.EMPTY_ANSWER, only.reason)
    }
    @Test
    fun `sanitizeTasks drops nbsp-only title`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(listOf(TaskDraft("\u00a0")))
        assertTrue(cleaned.isEmpty())
        assertFalse(GroupAiSharePolicy.canPersistTasks(listOf(TaskDraft("\u00a0"))))
    }
    @Test
    fun `shareAssistantMode trims nbsp-only mode to null`() {
        assertNull(GroupAiSharePolicy.shareAssistantMode("\u00a0"))
        assertEquals("tasks", GroupAiSharePolicy.shareAssistantMode("\u00a0tasks\u00a0"))
    }
    @Test
    fun `closed loop ready keeps tasks with dropped dueAt`() {
        // dueAt 非法只会被消毒成 null，不会丢任务：标题在，闭环就绪就不受影响。
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("a", dueAt = 0L))
            )
        )
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("a", dueAt = -5L))
            )
        )
    }
    @Test
    fun `closed loop ready at and beyond max task save count`() {
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = List(30) { i -> TaskDraft("t$i") }
            )
        )
        // 31 条消毒后截断为 30 条，仍非空：上限不破坏就绪判定。
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = List(31) { i -> TaskDraft("t$i") }
            )
        )
    }

    @Test
    fun `already shared long answer carries empty body`() {
        val decision = GroupAiSharePolicy.decideShare(true, "x".repeat(5000), alreadyShared = true)
        assertFalse(decision.allowed)
        assertEquals("", decision.body)
        assertEquals(BlockReason.ALREADY_SHARED, decision.reason)
    }

    @Test
    fun `shareAssistantMode truncates 41 chars to 40`() {
        assertEquals("x".repeat(40), GroupAiSharePolicy.shareAssistantMode("x".repeat(41)))
    }

    @Test
    fun `sanitize keeps smallest positive dueAt`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(
                TaskDraft(title = "t", dueAt = 1L),
                TaskDraft(title = "u", dueAt = Long.MIN_VALUE)
            )
        )
        assertEquals(1L, cleaned[0].dueAt)
        assertNull(cleaned[1].dueAt)
    }

    @Test
    fun `closed loop not ready for nbsp-only task titles`() {
        assertFalse(GroupAiSharePolicy.canPersistTasks(listOf(TaskDraft("\u00a0"))))
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("\u00a0"))
            )
        )
    }

    @Test
    fun `decideShare truncates emoji answer by code units`() {
        // take 按 UTF-16 码元计：2500 个表情（5000 码元）截断为 4000 码元，即 2000 个完整表情。
        val decision = GroupAiSharePolicy.decideShare(true, "\uD83D\uDE00".repeat(2500))
        assertTrue(decision.allowed)
        assertEquals(4000, decision.body.length)
        assertEquals("\uD83D\uDE00".repeat(2000), decision.body)
    }


    @Test
    fun `decideShare trims carriage returns at ends`() {
        val decision = GroupAiSharePolicy.decideShare(true, " \r\nhello\r\n ")
        assertTrue(decision.allowed)
        assertEquals("hello", decision.body)
    }

    @Test
    fun `decideShare keeps internal carriage return`() {
        val decision = GroupAiSharePolicy.decideShare(true, "a\rb")
        assertTrue(decision.allowed)
        assertEquals("a\rb", decision.body)
    }

    @Test
    fun `sanitize owner trims before capping`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", owner = "  " + "y".repeat(150)))
        )
        assertEquals("y".repeat(100), cleaned.single().owner)
    }

    @Test
    fun `sanitize dueText trims before capping`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueText = "  " + "z".repeat(130)))
        )
        assertEquals("z".repeat(120), cleaned.single().dueText)
    }

    @Test
    fun `share meta mode trims before capping`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("  " + "m".repeat(50))
        assertEquals("m".repeat(40), meta["aiAssistantMode"])
        assertEquals(true, meta["aiAssisted"])
        assertEquals(false, meta["systemIdentity"])
    }

    @Test
    fun `sanitizeTasks nulls zero dueAt and keeps null dueAt task`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(
                TaskDraft(title = "t", dueAt = 0L),
                TaskDraft(title = "u", dueAt = null)
            )
        )
        assertEquals(2, cleaned.size)
        assertNull(cleaned[0].dueAt)
        assertNull(cleaned[1].dueAt)
    }

    @Test
    fun `sanitizeTasks nulls nbsp-only dueText`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueText = "\u00a0"))
        )
        assertEquals(1, cleaned.size)
        assertNull(cleaned.single().dueText)
    }

    @Test
    fun `decideShare keeps internal nbsp`() {
        val decision = GroupAiSharePolicy.decideShare(true, "a\u00a0b")
        assertTrue(decision.allowed)
        assertEquals("a\u00a0b", decision.body)
    }

    @Test
    fun `canPersistTasks stays true beyond task cap`() {
        val tasks = List(31) { i -> TaskDraft(title = "t$i") }
        assertEquals(30, GroupAiSharePolicy.sanitizeTasks(tasks).size)
        assertTrue(GroupAiSharePolicy.canPersistTasks(tasks))
    }

    @Test
    fun `share meta nbsp mode nulls mode keeps keys`() {
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("\u00a0")
        assertNull(meta["aiAssistantMode"])
        assertEquals(true, meta["aiAssisted"])
        assertEquals(false, meta["systemIdentity"])
        assertEquals(setOf("aiAssisted", "aiAssistantMode", "systemIdentity"), meta.keys)
    }

    @Test
    fun `shareAssistantMode keeps exact 40 chars`() {
        // 恰好 40 字不截断，41 字才截断。
        val meta = GroupAiSharePolicy.shareAsCurrentUserMeta("m".repeat(40))
        assertEquals("m".repeat(40), meta["aiAssistantMode"])
    }

    @Test
    fun `sanitizeTasks nulls negative dueAt`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueAt = -5L))
        )
        assertEquals(1, cleaned.size)
        assertNull(cleaned.single().dueAt)
    }

    @Test
    fun `sanitize owner keeps exact 100 chars`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", owner = "y".repeat(100)))
        )
        assertEquals("y".repeat(100), cleaned.single().owner)
    }

    @Test
    fun `canPersistTasks false for empty list`() {
        assertFalse(GroupAiSharePolicy.canPersistTasks(emptyList()))
    }

    @Test
    fun `decideShare null answer in group is empty answer`() {
        val decision = GroupAiSharePolicy.decideShare(true, null)
        assertFalse(decision.allowed)
        assertEquals("", decision.body)
        assertEquals(BlockReason.EMPTY_ANSWER, decision.reason)
    }


    @Test
    fun `decideShare non-group null answer is not group`() {
        val decision = GroupAiSharePolicy.decideShare(false, null)
        assertFalse(decision.allowed)
        assertEquals("", decision.body)
        assertEquals(BlockReason.NOT_GROUP, decision.reason)
    }

    @Test
    fun `decideShare already shared beats null answer`() {
        val decision = GroupAiSharePolicy.decideShare(true, null, alreadyShared = true)
        assertFalse(decision.allowed)
        assertEquals("", decision.body)
        assertEquals(BlockReason.ALREADY_SHARED, decision.reason)
    }

    @Test
    fun `closed loop with no tasks is ready when share is confirmable`() {
        assertTrue(GroupAiSharePolicy.isClosedLoopReady(isGroup = true, answer = "summary"))
    }

    @Test
    fun `sanitizeTasks trims tab-wrapped titles`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "\t spaced \t"))
        )
        assertEquals(1, cleaned.size)
        assertEquals("spaced", cleaned.single().title)
    }

    @Test
    fun `closed loop already shared with no tasks is not ready`() {
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true, answer = "summary", alreadyShared = true
            )
        )
    }

    @Test
    fun `decideShare tab newline padded just under max keeps full body`() {
        val answer = "x".repeat(3999)
        val decision = GroupAiSharePolicy.decideShare(true, "\t\n" + answer + "\n\t")
        assertTrue(decision.allowed)
        assertEquals(answer, decision.body)
        assertEquals(3999, decision.body.length)
    }

    @Test
    fun `sanitizeTasks keeps title-only task with all other fields null`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = " t ", owner = null, dueText = null, dueAt = null))
        )
        assertEquals(listOf(TaskDraft(title = "t")), cleaned)
    }

    @Test
    fun `shareAssistantMode trims tab wrapped mode`() {
        assertEquals("tasks", GroupAiSharePolicy.shareAssistantMode("\ttasks\n"))
        assertEquals("tasks", GroupAiSharePolicy.shareAsCurrentUserMeta("\ttasks\n")["aiAssistantMode"])
    }

    @Test
    fun `closed loop ready ignores dropped dueAt and blank tasks together`() {
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("  ", dueAt = 0L), TaskDraft("a", dueAt = -5L))
            )
        )
    }

    @Test
    fun `blocked share decisions are equal regardless of the rejected answer`() {
        assertEquals(
            GroupAiSharePolicy.decideShare(false, "a"),
            GroupAiSharePolicy.decideShare(false, "b")
        )
        assertEquals(
            GroupAiSharePolicy.decideShare(true, null),
            GroupAiSharePolicy.decideShare(true, "   ")
        )
    }

    @Test
    fun `decideShare padded answer at exactly max keeps full body`() {
        val answer = "x".repeat(4_000)
        val decision = GroupAiSharePolicy.decideShare(true, "\t\n" + answer + "\n\t")
        assertTrue(decision.allowed)
        assertEquals(answer, decision.body)
        assertEquals(4_000, decision.body.length)
    }

    @Test
    fun `canPersistTasks stays true for a single title over the char cap`() {
        val tasks = listOf(TaskDraft("x".repeat(361)))
        assertTrue(GroupAiSharePolicy.canPersistTasks(tasks))
        assertEquals(360, GroupAiSharePolicy.sanitizeTasks(tasks).single().title.length)
    }

    @Test
    fun `sanitizeTasks keeps the first thirty tasks in order`() {
        val tasks = (0 until 35).map { TaskDraft("t$it") }
        val cleaned = GroupAiSharePolicy.sanitizeTasks(tasks)
        assertEquals(30, cleaned.size)
        assertEquals((0 until 30).map { "t$it" }, cleaned.map { it.title })
    }

    @Test
    fun `closed loop is not ready when every task is invalid`() {
        assertFalse(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "ok",
                tasks = listOf(TaskDraft("   "), TaskDraft(""))
            )
        )
    }

    @Test
    fun `canPersistTasks stays false for blanks beyond the task cap`() {
        assertFalse(GroupAiSharePolicy.canPersistTasks((0 until 35).map { TaskDraft("   ") }))
    }

    @Test
    fun `sanitizeTasks keeps internal double spaces in owner`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", owner = "a  b"))
        )
        assertEquals("a  b", cleaned.single().owner)
    }

    @Test
    fun `sanitizeTasks keeps internal newline in dueText`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueText = "a\nb"))
        )
        assertEquals("a\nb", cleaned.single().dueText)
    }

    @Test
    fun `sanitizeTasks drops middle invalid task but keeps order`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft("first"), TaskDraft("   "), TaskDraft("second"))
        )
        assertEquals(listOf("first", "second"), cleaned.map { it.title })
    }

    @Test
    fun `sanitizeTasks keeps padded title at exactly max chars`() {
        val tasks = listOf(TaskDraft(" " + "x".repeat(360) + " "))
        assertEquals(360, GroupAiSharePolicy.sanitizeTasks(tasks).single().title.length)
        assertTrue(GroupAiSharePolicy.canPersistTasks(tasks))
    }

    @Test
    fun `sanitizeTasks keeps internal tab in title`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft("a\tb"))
        )
        assertEquals("a\tb", cleaned.single().title)
    }

    @Test
    fun `decideShare keeps internal double spaces`() {
        val decision = GroupAiSharePolicy.decideShare(true, "  a  b  ")
        assertTrue(decision.allowed)
        assertEquals("a  b", decision.body)
    }

    @Test
    fun `shareAssistantMode newline-only mode nulls`() {
        assertNull(GroupAiSharePolicy.shareAssistantMode("\n"))
    }

    @Test
    fun `sanitizeTasks keeps internal double spaces in dueText`() {
        val cleaned = GroupAiSharePolicy.sanitizeTasks(
            listOf(TaskDraft(title = "t", dueText = "a  b"))
        )
        assertEquals("a  b", cleaned.single().dueText)
    }

    @Test
    fun `canPersistTasks true with one valid among many blanks beyond cap`() {
        val tasks = List(35) { TaskDraft("   ") } + TaskDraft("ok")
        assertTrue(GroupAiSharePolicy.canPersistTasks(tasks))
    }

    @Test
    fun `closed loop ready at max share chars and max task count`() {
        assertTrue(
            GroupAiSharePolicy.isClosedLoopReady(
                isGroup = true,
                answer = "x".repeat(4000),
                tasks = List(30) { i -> TaskDraft("t$i") }
            )
        )
    }

}
