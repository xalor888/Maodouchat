// GroupAiTaskDrafts sanitize/canPersist (145th block). Oracle
// reimplements the two functions from production text: title is
// trimmed and cut to 360 chars (blank titles drop the task), owner
// and dueText are trimmed and cut to 100/120 chars (blank becomes
// null), dueAt survives only when positive, and at most 30 tasks
// survive. GroupAiSharePolicy only delegates here; pinned tests
// cover it through that delegate, this file adds the random oracle.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Java Character.isWhitespace set (28 units); Kotlin trim() uses
// exactly this, not the <= 0x20 rule.
private val WS145 = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', '\u0020', '\u00A0', '\u1680', '\u2000', '\u2001',
    '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
    '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

private fun trim145(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i] in WS145) i++
    while (j > i && s[j - 1] in WS145) j--
    return s.substring(i, j)
}

private fun sanitizeTasksOracle145(tasks: List<TaskDraft>): List<TaskDraft> =
    tasks.asSequence()
        .mapNotNull { task ->
            val title = trim145(task.title).take(360)
            if (title.isEmpty()) null
            else TaskDraft(
                title = title,
                owner = task.owner?.let { trim145(it).take(100) }?.takeIf { it.isNotEmpty() },
                dueText = task.dueText?.let { trim145(it).take(120) }?.takeIf { it.isNotEmpty() },
                dueAt = task.dueAt?.takeIf { it > 0L }
            )
        }
        .take(30)
        .toList()

private fun canPersistTasksOracle145(tasks: List<TaskDraft>): Boolean =
    sanitizeTasksOracle145(tasks).isNotEmpty()

private fun sanitizeTasksProduction145(tasks: List<TaskDraft>): List<TaskDraft> =
    GroupAiTaskDrafts.sanitizeTasks(tasks)

private fun canPersistTasksProduction145(tasks: List<TaskDraft>): Boolean =
    GroupAiTaskDrafts.canPersistTasks(tasks)

// ---------- generators (mirrored by Oracle145 GEN, seed 145003) ----------

private val TITLE_WORD_POOL145 = arrayOf(
    "", " ", "  x  ", "task", "a".repeat(359), "a".repeat(360), "a".repeat(361),
    "é", "x".repeat(500), "\t\n", " a b ", "…", "ab", "  ", "x".repeat(100),
)
private val OWNER_POOL145 = arrayOf(
    "", " ", "Alice", "a".repeat(99), "a".repeat(100), "a".repeat(101),
    "  bob  ", "é", "x".repeat(200), "\t\n",
)
private val DUE_TEXT_POOL145 = arrayOf(
    "", " ", "tomorrow", "a".repeat(119), "a".repeat(120), "a".repeat(121),
    "  Fri  ", "é", "x".repeat(200),
)
private val DUE_AT_POOL145 = arrayOf<Long?>(null, 0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1_700_000_000_000L)

private fun randomTitle145(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 12)
    repeat(len) {
        when (rnd.nextInt(5)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.append('0' + rnd.nextInt(10))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            3 -> sb.append(TITLE_WORD_POOL145[rnd.nextInt(TITLE_WORD_POOL145.size)])
            else -> sb.append(WS145.elementAt(rnd.nextInt(WS145.size)))
        }
    }
    return sb.toString()
}

private fun randomOwner145(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> OWNER_POOL145[rnd.nextInt(OWNER_POOL145.size)]
    2 -> randomTitle145(rnd)
    else -> WS145.elementAt(rnd.nextInt(WS145.size)).toString()
}

private fun randomDueText145(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> DUE_TEXT_POOL145[rnd.nextInt(DUE_TEXT_POOL145.size)]
    2 -> randomTitle145(rnd)
    else -> WS145.elementAt(rnd.nextInt(WS145.size)).toString()
}

private fun randomDueAt145(rnd: Random): Long? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> DUE_AT_POOL145[rnd.nextInt(DUE_AT_POOL145.size)]
    2 -> rnd.nextLong()
    else -> rnd.nextLong(-1000L, 1000L)
}

private fun randomTask145(rnd: Random): TaskDraft =
    TaskDraft(randomTitle145(rnd), randomOwner145(rnd), randomDueText145(rnd), randomDueAt145(rnd))

private fun randomTasks145(rnd: Random): List<TaskDraft> {
    val n = if (rnd.nextInt(10) == 0) rnd.nextInt(28, 36) else rnd.nextInt(0, 6)
    return List(n) { randomTask145(rnd) }
}

class GroupAiTaskDraftsFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            emptyList<TaskDraft>(),
            listOf(TaskDraft("")),
            listOf(TaskDraft("  ")),
            listOf(TaskDraft("  x  ")),
            listOf(TaskDraft("a".repeat(359))),
            listOf(TaskDraft("a".repeat(360))),
            listOf(TaskDraft("a".repeat(361))),
            listOf(TaskDraft("x".repeat(500))),
            listOf(TaskDraft("t", owner = "  ")),
            listOf(TaskDraft("t", owner = "  bob  ")),
            listOf(TaskDraft("t", owner = "a".repeat(99))),
            listOf(TaskDraft("t", owner = "a".repeat(100))),
            listOf(TaskDraft("t", owner = "a".repeat(101))),
            listOf(TaskDraft("t", dueText = "  ")),
            listOf(TaskDraft("t", dueText = "  Fri  ")),
            listOf(TaskDraft("t", dueText = "a".repeat(119))),
            listOf(TaskDraft("t", dueText = "a".repeat(120))),
            listOf(TaskDraft("t", dueText = "a".repeat(121))),
            listOf(TaskDraft("t", dueAt = 0L)),
            listOf(TaskDraft("t", dueAt = -1L)),
            listOf(TaskDraft("t", dueAt = 1L)),
            listOf(TaskDraft("t", dueAt = Long.MAX_VALUE)),
            listOf(TaskDraft("t", dueAt = Long.MIN_VALUE)),
            listOf(TaskDraft("t", owner = "  ", dueText = "  ", dueAt = 0L)),
            List(30) { TaskDraft("t$it") },
            List(31) { TaskDraft("t$it") },
            List(35) { TaskDraft("t$it") },
            List(10) { TaskDraft("  ") } + List(25) { TaskDraft("t$it") },
            listOf(TaskDraft("\t\n x \u00A0", owner = "\u00A0", dueText = "\u00A0")),
        )
        for (tasks in cases) {
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
            assertEquals(canPersistTasksOracle145(tasks), canPersistTasksProduction145(tasks))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(145003)
        repeat(1500) {
            val tasks = randomTasks145(rnd)
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
            assertEquals(canPersistTasksOracle145(tasks), canPersistTasksProduction145(tasks))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Title length sweep around the take(360) cut, plus whitespace-only.
        for (len in 0..365) {
            val tasks = listOf(TaskDraft("a".repeat(len)))
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
        }
        for (len in 0..5) {
            val tasks = listOf(TaskDraft(" ".repeat(len) + "a"))
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
            assertEquals(canPersistTasksOracle145(tasks), canPersistTasksProduction145(tasks))
        }
        // Single-unit titles across ASCII + whitespace + boundary units.
        val units = (0..0x7F).toList() + WS145.map { it.code } +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            val tasks = listOf(TaskDraft(ch, owner = ch, dueText = ch))
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
        }
        // Owner/dueText length sweeps around their take(100)/take(120) cuts.
        for (len in 0..105) {
            val tasks = listOf(TaskDraft("t", owner = "b".repeat(len)))
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
        }
        for (len in 0..125) {
            val tasks = listOf(TaskDraft("t", dueText = "c".repeat(len)))
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
        }
        // dueAt boundary sweep.
        for (v in listOf(Long.MIN_VALUE, -2L, -1L, 0L, 1L, 2L, Long.MAX_VALUE)) {
            val tasks = listOf(TaskDraft("t", dueAt = v))
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
            assertEquals(canPersistTasksOracle145(tasks), canPersistTasksProduction145(tasks))
        }
        // List length sweep around the take(30) cap.
        for (n in 0..35) {
            val tasks = List(n) { TaskDraft("t$it") }
            assertEquals(sanitizeTasksOracle145(tasks), sanitizeTasksProduction145(tasks))
            assertEquals(canPersistTasksOracle145(tasks), canPersistTasksProduction145(tasks))
        }
    }
}
