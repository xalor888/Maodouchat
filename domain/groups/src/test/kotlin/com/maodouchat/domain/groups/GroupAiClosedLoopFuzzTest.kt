package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Group AI closed-loop readiness restated by hand. Ground truth, read
// from the closed-loop readiness file and its three delegates:
//   - decideShare: non-group refuses first, then already-shared, then
//     a blank-after-trim answer; otherwise the trimmed body is shared,
//     capped at 4000 chars.
//   - shareAsCurrentUserMeta("answer") always yields systemIdentity =
//     false and aiAssisted = true, so both meta gates always pass.
//   - canPersistTasks: at least one task survives sanitizeTasks.
//     sanitizeTasks drops blank titles, trims titles to 360 chars,
//     owners to 100, due texts to 120, keeps dueAt only when > 0,
//     and keeps at most 30 tasks.
// Only isClosedLoopReady is fuzzed here: its delegates are already
// covered by blocks 133 (decideShare / shareMeta) and 145 (task
// drafts), so this file tests the composition, not the delegates.
internal data class TaskDraft182(
    val title: String,
    val owner: String?,
    val dueText: String?,
    val dueAt: Long?,
)

internal fun decideShareOracle182(
    isGroup: Boolean,
    answer: String?,
    alreadyShared: Boolean,
): Pair<Boolean, String> {
    if (!isGroup) return false to ""
    if (alreadyShared) return false to ""
    val body = (answer ?: "").trim()
    if (body.isEmpty()) return false to ""
    return true to body.take(4_000)
}

internal fun sanitizeTasksOracle182(tasks: List<TaskDraft182>): List<TaskDraft182> =
    tasks.asSequence()
        .mapNotNull { task ->
            val title = task.title.trim().take(360)
            if (title.isEmpty()) null
            else TaskDraft182(
                title = title,
                owner = task.owner?.trim()?.take(100)?.takeIf { it.isNotEmpty() },
                dueText = task.dueText?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
                dueAt = task.dueAt?.takeIf { it > 0L },
            )
        }
        .take(30)
        .toList()

internal fun canPersistTasksOracle182(tasks: List<TaskDraft182>): Boolean =
    sanitizeTasksOracle182(tasks).isNotEmpty()

internal fun closedLoopOracle182(
    isGroup: Boolean,
    answer: String?,
    alreadyShared: Boolean,
    tasks: List<TaskDraft182>,
): Boolean {
    val (allowed, _) = decideShareOracle182(isGroup, answer, alreadyShared)
    if (!allowed) return false
    if (tasks.isNotEmpty() && !canPersistTasksOracle182(tasks)) return false
    return true
}

internal fun escJsonClosed182(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

// ---------- production ----------

internal fun closedLoopProduction182(
    isGroup: Boolean,
    answer: String?,
    alreadyShared: Boolean,
    tasks: List<TaskDraft182>,
): Boolean = GroupAiClosedLoopReadiness.isClosedLoopReady(
    isGroup = isGroup,
    answer = answer,
    tasks = tasks.map { TaskDraft(it.title, it.owner, it.dueText, it.dueAt) },
    alreadyShared = alreadyShared,
)

// ---------- generators ----------

internal data class ClosedLoopCase182(
    val isGroup: Boolean,
    val answer: String?,
    val alreadyShared: Boolean,
    val tasks: List<TaskDraft182>,
)

internal val CLOSED_ANSWERS182: List<String?> = listOf(
    null, "", " ", "   ", "\t\n", "\u00A0", "x", "answer", "  answer  ",
    "a".repeat(3_999), "a".repeat(4_000), "a".repeat(4_001),
    "  " + "a".repeat(4_001) + "  ", "答案", "hello world", "x".repeat(5_000),
)

internal val CLOSED_TITLES182: List<String> = listOf(
    "", " ", "\t", "task", "  task  ", "x".repeat(359), "x".repeat(360),
    "x".repeat(361), "x".repeat(500), "标题", "\u00A0",
)

internal val CLOSED_OWNERS182: List<String?> = listOf(
    null, "", " ", "alice", "  bob  ", "u".repeat(99), "u".repeat(100),
    "u".repeat(101), "u".repeat(200),
)

internal val CLOSED_DUE_TEXTS182: List<String?> = listOf(
    null, "", " ", "tomorrow", "  friday  ", "d".repeat(119), "d".repeat(120),
    "d".repeat(121), "d".repeat(200),
)

internal val CLOSED_DUE_ATS182: List<Long?> = listOf(
    null, 0L, -1L, 1L, 1_000L, -1_000L, Long.MAX_VALUE, Long.MIN_VALUE,
)

internal fun randomClosedLoopCase182(rnd: Random): ClosedLoopCase182 {
    val isGroup = rnd.nextBoolean()
    val answer = when (rnd.nextInt(6)) {
        0 -> null
        1 -> CLOSED_ANSWERS182[rnd.nextInt(CLOSED_ANSWERS182.size)]
        2 -> "x".repeat(rnd.nextInt(0, 4_200))
        3 -> buildString {
            val alphabet = "ab \t\n\u00A0\u2003"
            repeat(rnd.nextInt(0, 30)) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
        4 -> "  " + "y".repeat(rnd.nextInt(0, 4_100)) + "  "
        else -> CLOSED_ANSWERS182[rnd.nextInt(CLOSED_ANSWERS182.size)]
    }
    val alreadyShared = rnd.nextBoolean()
    val taskCount = when (rnd.nextInt(5)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        2 -> rnd.nextInt(28, 33)
        3 -> rnd.nextInt(0, 35)
        else -> rnd.nextInt(0, 3)
    }
    val tasks = ArrayList<TaskDraft182>()
    repeat(taskCount) {
        tasks.add(
            TaskDraft182(
                title = CLOSED_TITLES182[rnd.nextInt(CLOSED_TITLES182.size)],
                owner = CLOSED_OWNERS182[rnd.nextInt(CLOSED_OWNERS182.size)],
                dueText = CLOSED_DUE_TEXTS182[rnd.nextInt(CLOSED_DUE_TEXTS182.size)],
                dueAt = CLOSED_DUE_ATS182[rnd.nextInt(CLOSED_DUE_ATS182.size)],
            )
        )
    }
    return ClosedLoopCase182(isGroup, answer, alreadyShared, tasks)
}

internal fun closedLoopCaseWireJson182(c: ClosedLoopCase182): String {
    val answerJson = if (c.answer == null) "null" else escJsonClosed182(c.answer)
    val tasksJson = c.tasks.joinToString(",") { t ->
        val owner = if (t.owner == null) "null" else escJsonClosed182(t.owner)
        val dueText = if (t.dueText == null) "null" else escJsonClosed182(t.dueText)
        val dueAt = t.dueAt?.toString() ?: "null"
        "{\"title\":${escJsonClosed182(t.title)},\"owner\":$owner,\"dueText\":$dueText,\"dueAt\":$dueAt}"
    }
    return "{\"group\":${c.isGroup},\"answer\":$answerJson,\"shared\":${c.alreadyShared},\"tasks\":[$tasksJson]}"
}

internal val CLOSED_PINNED182: List<ClosedLoopCase182> = listOf(
    ClosedLoopCase182(false, "x", false, listOf()),
    ClosedLoopCase182(true, "x", true, listOf()),
    ClosedLoopCase182(true, null, false, listOf()),
    ClosedLoopCase182(true, "", false, listOf()),
    ClosedLoopCase182(true, "   ", false, listOf()),
    ClosedLoopCase182(true, "answer", false, listOf()),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("task", null, null, null))),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("", null, null, null))),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("   ", null, null, null))),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("task", "alice", "friday", 1_000L))),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("task", "alice", "friday", 0L))),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("task", "alice", "friday", -5L))),
    ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("x".repeat(361), null, null, null))),
    ClosedLoopCase182(true, "a".repeat(4_001), false, listOf()),
    ClosedLoopCase182(true, "a".repeat(4_000), false, listOf()),
    ClosedLoopCase182(false, "x", true, listOf(TaskDraft182("task", null, null, null))),
)

class GroupAiClosedLoopFuzzTest {

    private fun checkClosedLoop182(c: ClosedLoopCase182) {
        assertEquals(
            closedLoopOracle182(c.isGroup, c.answer, c.alreadyShared, c.tasks),
            closedLoopProduction182(c.isGroup, c.answer, c.alreadyShared, c.tasks),
            "closedLoop($c)",
        )
    }

    @Test
    fun pinnedClosedLoop() {
        for (c in CLOSED_PINNED182) checkClosedLoop182(c)
    }

    @Test
    fun randomClosedLoopMatchesProduction() {
        val rnd = Random(182004)
        repeat(3000) { checkClosedLoop182(randomClosedLoopCase182(rnd)) }
    }

    @Test
    fun sweepClosedLoopBoundaries() {
        // Answer length around the 4000-char cap.
        for (n in listOf(3_999, 4_000, 4_001, 4_002)) {
            checkClosedLoop182(ClosedLoopCase182(true, "a".repeat(n), false, listOf()))
            checkClosedLoop182(ClosedLoopCase182(true, "  " + "a".repeat(n) + "  ", false, listOf()))
        }
        // Title length around the 360-char cap.
        for (n in listOf(359, 360, 361)) {
            checkClosedLoop182(
                ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("x".repeat(n), null, null, null)))
            )
        }
        // Task count around the 30-task cap, with all-blank titles.
        for (n in listOf(29, 30, 31, 32)) {
            val blank = List(n) { TaskDraft182("", null, null, null) }
            checkClosedLoop182(ClosedLoopCase182(true, "answer", false, blank))
            val mixed = List(n) { i ->
                if (i == n - 1) TaskDraft182("last", null, null, null) else TaskDraft182("", null, null, null)
            }
            checkClosedLoop182(ClosedLoopCase182(true, "answer", false, mixed))
        }
        // Owner / due-text caps and dueAt sign.
        for (n in listOf(99, 100, 101)) {
            checkClosedLoop182(
                ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("t", "u".repeat(n), null, null)))
            )
        }
        for (n in listOf(119, 120, 121)) {
            checkClosedLoop182(
                ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("t", null, "d".repeat(n), null)))
            )
        }
        for (dueAt in listOf(-1L, 0L, 1L)) {
            checkClosedLoop182(
                ClosedLoopCase182(true, "answer", false, listOf(TaskDraft182("t", null, null, dueAt)))
            )
        }
    }
}
