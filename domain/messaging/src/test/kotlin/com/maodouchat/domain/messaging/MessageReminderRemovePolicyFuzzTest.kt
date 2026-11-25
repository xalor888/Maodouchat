// Fuzz / random-oracle tests for MessageReminderRemovePolicy
// (checklist line 931, 159th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: removing by
// id drops every reminder whose id equals the given id exactly
// (case-sensitive String equality) and keeps the rest in order; only
// the id field matters, the other fields ride along untouched.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReminder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun remindRemoveOracle159(
    reminders: List<MessageReminder>,
    reminderId: String,
): List<MessageReminder> = reminders.filterNot { it.id == reminderId }

// ---------- generators ----------

internal val REMIND_ID_POOL159 = listOf(
    "r1", "r2", "R1", "r1 ", " r1", "", " ", "x", "m1", "u1", "ß", "ẞ",
    "İ", "ı", "ﬀ", "FF", "r1\u00a0", "r1\u0085",
)

internal val REMIND_ALPHABET159 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003r12mxuR\u0130\u0131I i\u00df\u1e9eK\u212a k\u017fsS\u00b5"

internal fun randomRemindChar159(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> REMIND_ALPHABET159[rnd.nextInt(REMIND_ALPHABET159.length)].toString()
}

internal fun randomRemindString159(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomRemindChar159(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal fun randomRemind159(rnd: Random, index: Int): MessageReminder =
    MessageReminder(
        id = if (rnd.nextInt(10) < 6) REMIND_ID_POOL159[rnd.nextInt(REMIND_ID_POOL159.size)] else randomRemindString159(rnd),
        chatId = "c$index",
        messageId = "m$index",
        messagePreview = randomRemindString159(rnd),
        remindAtMillis = rnd.nextLong(),
        createdAtMillis = rnd.nextLong(),
        ownerUserId = "u$index",
    )

internal data class RemindCase159(
    val reminders: List<MessageReminder>,
    val reminderId: String,
)

internal fun randomRemindCase159(rnd: Random): RemindCase159 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    val reminders = List(count) { i -> randomRemind159(rnd, i) }
    val reminderId = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> if (reminders.isNotEmpty()) reminders[rnd.nextInt(reminders.size)].id else "r1"
        2 -> REMIND_ID_POOL159[rnd.nextInt(REMIND_ID_POOL159.size)]
        else -> randomRemindString159(rnd)
    }
    return RemindCase159(reminders, reminderId)
}

internal fun remindJsonEscape159(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun remindJson159(reminder: MessageReminder): String {
    val sb = StringBuilder()
    sb.append("{\"id\":\"").append(remindJsonEscape159(reminder.id)).append("\"")
    sb.append(",\"chatId\":\"").append(remindJsonEscape159(reminder.chatId)).append("\"")
    sb.append(",\"messageId\":\"").append(remindJsonEscape159(reminder.messageId)).append("\"")
    sb.append(",\"preview\":\"").append(remindJsonEscape159(reminder.messagePreview)).append("\"")
    sb.append(",\"remindAt\":").append(reminder.remindAtMillis)
    sb.append(",\"createdAt\":").append(reminder.createdAtMillis)
    sb.append(",\"owner\":\"").append(remindJsonEscape159(reminder.ownerUserId)).append("\"}")
    return sb.toString()
}

internal fun remindRemoveWireJson159(case: RemindCase159): String {
    val sb = StringBuilder()
    sb.append("{\"reminders\":[")
    case.reminders.forEachIndexed { i, r ->
        if (i > 0) sb.append(",")
        sb.append(remindJson159(r))
    }
    sb.append("],\"id\":\"").append(remindJsonEscape159(case.reminderId)).append("\"}")
    return sb.toString()
}

class MessageReminderRemovePolicyFuzzTest {

    private fun check(reminders: List<MessageReminder>, reminderId: String) {
        assertEquals(
            remindRemoveOracle159(reminders, reminderId),
            MessageReminderRemovePolicy.removeById(reminders, reminderId),
        )
    }

    private fun reminder(id: String): MessageReminder =
        MessageReminder(
            id = id, chatId = "c", messageId = "m", messagePreview = "p",
            remindAtMillis = 0L, createdAtMillis = 0L, ownerUserId = "u",
        )

    @Test
    fun pinnedBoundaries() {
        val list = listOf(
            reminder("r1"), reminder("r2"), reminder("r1"), reminder("R1"),
            reminder(""), reminder("r1 "),
        )
        // Drops every exact match, keeps the rest in order.
        assertEquals(
            listOf("r2", "R1", "", "r1 "),
            MessageReminderRemovePolicy.removeById(list, "r1").map { it.id },
        )
        assertEquals(
            listOf("r2", "R1", "", "r1 "),
            remindRemoveOracle159(list, "r1").map { it.id },
        )
        // Id comparison is exact: case and surrounding blanks matter.
        assertEquals(
            listOf("r1", "r2", "r1", "", "r1 "),
            MessageReminderRemovePolicy.removeById(list, "R1").map { it.id },
        )
        assertEquals(
            listOf("r1", "r2", "r1", "R1", ""),
            MessageReminderRemovePolicy.removeById(list, "r1 ").map { it.id },
        )
        // Empty id removes only the empty-id reminders.
        assertEquals(
            listOf("r1", "r2", "r1", "R1", "r1 "),
            MessageReminderRemovePolicy.removeById(list, "").map { it.id },
        )
        // Absent id removes nothing.
        assertEquals(
            list.map { it.id },
            MessageReminderRemovePolicy.removeById(list, "zzz").map { it.id },
        )
        // Empty list stays empty.
        assertEquals(emptyList(), MessageReminderRemovePolicy.removeById(emptyList(), "r1"))
        // Removing the only id empties the list.
        assertEquals(
            emptyList(),
            MessageReminderRemovePolicy.removeById(listOf(reminder("r1")), "r1"),
        )
        // Other fields do not affect the decision.
        val varied = listOf(
            MessageReminder("r1", "c1", "m1", "p1", 100L, 200L, "u1"),
            MessageReminder("r1", "c2", "m2", "p2", -5L, Long.MAX_VALUE, "u2"),
            MessageReminder("r2", "c3", "m3", "p3", 0L, 0L, "u3"),
        )
        assertEquals(
            listOf("r2"),
            MessageReminderRemovePolicy.removeById(varied, "r1").map { it.id },
        )
        for (id in REMIND_ID_POOL159) {
            check(listOf(reminder(id)), id)
            check(list, id)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(159003)
        repeat(2000) {
            val case = randomRemindCase159(rnd)
            check(case.reminders, case.reminderId)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool id against every pool target id, single reminder.
        for (id in REMIND_ID_POOL159) {
            for (target in REMIND_ID_POOL159) {
                check(listOf(reminder(id)), target)
            }
        }
        // Every length 0..30 of all-same-id lists, target present and absent.
        for (n in 0..30) {
            val allSame = List(n) { reminder("r1") }
            check(allSame, "r1")
            check(allSame, "r2")
            check(allSame, "")
        }
        // Fixed mixed list against every pool target id.
        val mixed = listOf(
            reminder("r1"), reminder("r2"), reminder("r1"), reminder("R1"),
            reminder(""), reminder("x"),
        )
        for (target in REMIND_ID_POOL159) {
            check(mixed, target)
        }
        // Long ids: "x" repeated 0..60, target "x" and "y".
        for (n in 0..60) {
            check(listOf(reminder("x".repeat(n))), "x")
            check(listOf(reminder("x".repeat(n))), "y")
        }
        // Every single alphabet char as id and target.
        for (c in REMIND_ALPHABET159) {
            check(listOf(reminder(c.toString())), c.toString())
            check(listOf(reminder(c.toString())), "x")
        }
    }
}
