// Fuzz / random-oracle tests for ChatParticipantsPolicy
// (checklist line 931, 133rd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: participants
// are filtered by exact UTF-16-unit id equality against the current
// user id, keeping input order and every other field untouched. No
// ChatParticipantsPolicy fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies; the bare name
// excludingCurrentUser also appears in no other fuzz test).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun cppExclOracle(participants: List<User>, currentUserId: String): List<User> =
    participants.filter { it.id != currentUserId }

// ---------- generators (mirrored by Oracle133 GEN, seed 133003) ----------

private val CPP_ID_POOL = arrayOf(
    "", " ", "u1", "u2", "a", "b", "a\uD800b", "\uDC00", "\uE000",
    "\uD83D\uDE00", "u10", "U1", "u1 ", " u1", "p-1", "p_1"
)

private val CPP_NAME_POOL = arrayOf(
    "", " ", "Alice", "alice", "ALICE", "Bob", "  Alice  ", "Al\u00A0ice",
    "A\uD800lice", "\uD83D\uDE00", "Zoe", "\u03A3", "a", "K"
)

private val CPP_LONG_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 1000L, Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private fun genCppId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CPP_ID_POOL[rnd.nextInt(CPP_ID_POOL.size)]
    else randomText(rnd, 6)

private fun genCppName(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CPP_NAME_POOL[rnd.nextInt(CPP_NAME_POOL.size)]
    else randomText(rnd, 8)

private fun genCppNameNullable(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else genCppName(rnd)

private fun genCppLong(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) CPP_LONG_POOL[rnd.nextInt(CPP_LONG_POOL.size)]
    else rnd.nextLong(-1_000_000_000L, 1_000_000_001L)

private fun genCppUser(rnd: Random): User = User(
    id = genCppId(rnd),
    name = genCppName(rnd),
    avatar = genCppNameNullable(rnd),
    email = genCppName(rnd),
    isOnline = rnd.nextBoolean(),
    status = genCppName(rnd),
    nickname = genCppNameNullable(rnd),
    lastSeen = genCppLong(rnd)
)

private fun genCppUserList(rnd: Random): List<User> {
    val n = rnd.nextInt(0, 6)
    return List(n) { genCppUser(rnd) }
}

private fun genCppCurrentId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CPP_ID_POOL[rnd.nextInt(CPP_ID_POOL.size)]
    else randomText(rnd, 6)

class ChatParticipantsPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val u1 = User("u1", "Alice")
        val u2 = User("u2", "bob", nickname = "Bobby")
        val u3 = User("u3", "Zoe", isOnline = true)
        // Empty list stays empty; no match keeps everyone, in order.
        assertEquals(emptyList(), ChatParticipantsPolicy.excludingCurrentUser(emptyList(), "u1"))
        assertEquals(
            listOf(u1, u2, u3),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), "u9")
        )
        // The current user is dropped wherever they sit; the rest keep order.
        assertEquals(
            listOf(u2, u3),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), "u1")
        )
        assertEquals(
            listOf(u1, u3),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), "u2")
        )
        assertEquals(
            listOf(u1, u2),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), "u3")
        )
        // Duplicate ids are all dropped; everyone dropped leaves empty.
        assertEquals(
            listOf(u2),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u1, u2), "u1")
        )
        assertEquals(
            emptyList(),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u1), "u1")
        )
        // Id equality is exact: case and surrounding space both matter.
        assertEquals(
            listOf(u1, u2, u3),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), "U1")
        )
        assertEquals(
            listOf(u1, u2, u3),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), "u1 ")
        )
        assertEquals(
            listOf(u1, u2, u3),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2, u3), " u1")
        )
        // An empty-string id matches only empty-string ids.
        val blank = User("", "Nameless")
        assertEquals(
            listOf(u1),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(blank, u1), "")
        )
        assertEquals(
            listOf(blank, u1),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(blank, u1), " ")
        )
        // Other fields never affect the filter and are carried through whole.
        assertEquals(
            listOf(u2),
            ChatParticipantsPolicy.excludingCurrentUser(listOf(u1, u2), "u1")
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle133.genCpp consumption order exactly, so seed
        // 133003 replays the same 1500 GEN lines drive133.py ran.
        val rnd = Random(133003)
        repeat(1500) {
            val participants = genCppUserList(rnd)
            val currentUserId = genCppCurrentId(rnd)
            assertEquals(
                cppExclOracle(participants, currentUserId),
                ChatParticipantsPolicy.excludingCurrentUser(participants, currentUserId)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val users = listOf(
            User("u1", "Alice"),
            User("u2", "bob", nickname = "Bobby"),
            User("u3", "Zoe", isOnline = true),
            User("u1", "Alice-again"),
            User("", "Nameless"),
            User("U1", "Upper")
        )
        // Every pool id against the fixed list, plus the empty list.
        for (id in CPP_ID_POOL) {
            assertEquals(
                cppExclOracle(users, id),
                ChatParticipantsPolicy.excludingCurrentUser(users, id)
            )
            assertEquals(
                emptyList(),
                ChatParticipantsPolicy.excludingCurrentUser(emptyList(), id)
            )
        }
        // Single-user lists through every pool id.
        for (u in users) {
            for (id in CPP_ID_POOL) {
                assertEquals(
                    cppExclOracle(listOf(u), id),
                    ChatParticipantsPolicy.excludingCurrentUser(listOf(u), id)
                )
            }
        }
        // Whitespace/control singles as current ids.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(
                cppExclOracle(users, s),
                ChatParticipantsPolicy.excludingCurrentUser(users, s)
            )
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(
                cppExclOracle(users, s),
                ChatParticipantsPolicy.excludingCurrentUser(users, s)
            )
        }
    }
}
