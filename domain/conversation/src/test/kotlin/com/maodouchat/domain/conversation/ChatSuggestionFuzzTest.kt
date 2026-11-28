// Fuzz / random-oracle tests for ChatSuggestion.chatNameForSuggestion
// (checklist line 931, 138th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a null chat
// yields null; otherwise the group name wins as-is (even a blank one --
// the chain only checks null, never blank), else the first participant's
// display name, else null when there is no participant. The display name
// itself falls back nickname -> name -> id, skipping blank parts.
// The trailing `?.name` fallback in production can never fire --
// displayName is a non-null String -- so the oracle omits it; the
// differential drive below covers the same inputs against production.
// No fuzz test covered this function before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files for ChatSuggestion / chatNameForSuggestion).
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

// The probed Kotlin whitespace set (fuzz-78): isBlank() treats exactly
// these units as blank; U+0085/U+200B/U+FEFF are not blank.
private fun isWs(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, in 0x1C..0x20 -> true
    0x00A0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
    in 0x2000..0x200A, in 0x2028..0x2029 -> true
    else -> false
}

private fun isBlankOracle(s: String): Boolean = s.all(::isWs)

private fun displayNameOracle(user: User): String =
    user.nickname?.takeIf { !isBlankOracle(it) }
        ?: user.name.takeIf { !isBlankOracle(it) }
        ?: user.id

private fun chatNameForSuggestionOracle(chat: Chat?): String? {
    if (chat == null) return null
    return chat.groupName ?: chat.participants.firstOrNull()?.let { displayNameOracle(it) }
}

// ---------- generators (mirrored by Oracle138 GEN, seed 138003) ----------

private val CNS_GROUP_POOL = arrayOf(
    "", " ", "Family", "  Group  ", "\u00A0", "a\uD800b", "\uD83D\uDE00", "x"
)

private val CNS_ID_POOL = arrayOf(
    "", " ", "me", "peer", "alice", "u1", "12345678", "123456789", "a b",
    "a\uD800b", "\uD83D\uDE00", "system", "x"
)

private val CNS_NAME_POOL = arrayOf(
    "", " ", "alice", "Alice", "bob", "   ", "\u00A0", "a b", "a\uD800b",
    "\uD83D\uDE00", "x", "\u0000", "12345678", "123456789", "Me", "peer"
)

private val CNS_NICK_POOL = arrayOf(
    "", " ", "Nick", "\u00A0", "a\uD800b", "\uD83D\uDE00"
)

private fun genCnsId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CNS_ID_POOL[rnd.nextInt(CNS_ID_POOL.size)]
    else randomText(rnd, 10)

private fun genCnsName(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CNS_NAME_POOL[rnd.nextInt(CNS_NAME_POOL.size)]
    else randomText(rnd, 12)

private fun genCnsNick(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> CNS_NICK_POOL[rnd.nextInt(CNS_NICK_POOL.size)]
    2 -> "a".repeat(rnd.nextInt(0, 13))
    else -> randomText(rnd, 10)
}

private fun genCnsGroup(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> CNS_GROUP_POOL[rnd.nextInt(CNS_GROUP_POOL.size)]
    else -> randomText(rnd, 12)
}

private data class CnsCase(
    val isNull: Boolean,
    val groupName: String?,
    val parts: List<User>,
)

private fun genCnsCase(rnd: Random): CnsCase {
    val isNull = rnd.nextInt(10) == 0
    val groupName = genCnsGroup(rnd)
    val nParts = rnd.nextInt(0, 4)
    val parts = List(nParts) {
        User(id = genCnsId(rnd), name = genCnsName(rnd), nickname = genCnsNick(rnd))
    }
    return CnsCase(isNull, groupName, parts)
}

private fun CnsCase.toChat(): Chat? =
    if (isNull) null else Chat(id = "c", participants = parts, groupName = groupName)

class ChatSuggestionFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // A null chat yields null; a named group wins over participants.
        assertNull(chatNameForSuggestion(null))
        assertEquals(
            "Family",
            chatNameForSuggestion(
                Chat(id = "c", participants = listOf(User(id = "p", name = "Pat")), groupName = "Family")
            )
        )
        // A blank group name is returned as-is -- the chain checks null,
        // never blank -- so it still beats the first participant.
        assertEquals(
            "",
            chatNameForSuggestion(
                Chat(id = "c", participants = listOf(User(id = "p", name = "Pat")), groupName = "")
            )
        )
        assertEquals(
            "   ",
            chatNameForSuggestion(
                Chat(id = "c", participants = listOf(User(id = "p", name = "Pat")), groupName = "   ")
            )
        )
        // No group name: the first participant's display name wins.
        assertEquals(
            "Pat",
            chatNameForSuggestion(
                Chat(id = "c", participants = listOf(User(id = "p", name = "Pat")), groupName = null)
            )
        )
        // No group name and no participants: null.
        assertNull(
            chatNameForSuggestion(Chat(id = "c", participants = emptyList(), groupName = null))
        )
        // Display-name fallback: nickname beats name, blank nickname
        // falls back to name, blank name falls back to id.
        assertEquals(
            "Nicky",
            chatNameForSuggestion(
                Chat(
                    id = "c",
                    participants = listOf(User(id = "p", name = "Pat", nickname = "Nicky")),
                    groupName = null,
                )
            )
        )
        assertEquals(
            "Pat",
            chatNameForSuggestion(
                Chat(
                    id = "c",
                    participants = listOf(User(id = "p", name = "Pat", nickname = "  ")),
                    groupName = null,
                )
            )
        )
        assertEquals(
            "p",
            chatNameForSuggestion(
                Chat(
                    id = "c",
                    participants = listOf(User(id = "p", name = "  ", nickname = null)),
                    groupName = null,
                )
            )
        )
        // Only the first participant counts, even when a later one is nicer.
        assertEquals(
            "First",
            chatNameForSuggestion(
                Chat(
                    id = "c",
                    participants = listOf(
                        User(id = "a", name = "First"),
                        User(id = "b", name = "Second", nickname = "Nicky"),
                    ),
                    groupName = null,
                )
            )
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle138.genCns consumption order exactly, so seed
        // 138003 replays the same 1500 GEN lines drive138.py ran.
        val rnd = Random(138003)
        repeat(1500) {
            val c = genCnsCase(rnd)
            val chat = c.toChat()
            assertEquals(chatNameForSuggestionOracle(chat), chatNameForSuggestion(chat))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Group name pool x participant shapes (none / one / two).
        val partShapes: List<List<User>> = listOf(
            emptyList(),
            listOf(User(id = "p", name = "Pat")),
            listOf(User(id = "p", name = "Pat", nickname = "Nicky")),
            listOf(User(id = "", name = ""), User(id = "b", name = "Second")),
        )
        for (groupName in listOf<String?>(null) + CNS_GROUP_POOL) {
            for (parts in partShapes) {
                val chat = Chat(id = "c", participants = parts, groupName = groupName)
                assertEquals(chatNameForSuggestionOracle(chat), chatNameForSuggestion(chat))
            }
        }
        // Participant display-name fallback matrix.
        val nicks: List<String?> = listOf(null, "", " ", "Nick", "\u00A0")
        val names = listOf("", " ", "Bob")
        val ids = listOf("", "peer", "123456789")
        for (nick in nicks) {
            for (name in names) {
                for (id in ids) {
                    val chat = Chat(
                        id = "c",
                        participants = listOf(User(id = id, name = name, nickname = nick)),
                        groupName = null,
                    )
                    assertEquals(chatNameForSuggestionOracle(chat), chatNameForSuggestion(chat))
                }
            }
        }
        // Every single low unit as a 1-char group name / participant
        // name / nickname / id.
        for (code in 0x00..0x7F) {
            val ch = code.toChar().toString()
            val byGroup = Chat(
                id = "c",
                participants = listOf(User(id = "p", name = "Pat")),
                groupName = ch,
            )
            assertEquals(chatNameForSuggestionOracle(byGroup), chatNameForSuggestion(byGroup))
            val byName = Chat(
                id = "c",
                participants = listOf(User(id = "p", name = ch)),
                groupName = null,
            )
            assertEquals(chatNameForSuggestionOracle(byName), chatNameForSuggestion(byName))
            val byNick = Chat(
                id = "c",
                participants = listOf(User(id = "p", name = "Pat", nickname = ch)),
                groupName = null,
            )
            assertEquals(chatNameForSuggestionOracle(byNick), chatNameForSuggestion(byNick))
            val byId = Chat(
                id = "c",
                participants = listOf(User(id = ch, name = "  ")),
                groupName = null,
            )
            assertEquals(chatNameForSuggestionOracle(byId), chatNameForSuggestion(byId))
        }
    }
}
