// Fuzz / random-oracle tests for ChatSenderDisplayName (checklist line
// 931, 138th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: senderDisplayName
// returns null for your own message; otherwise it takes the first
// non-blank of (direct: contact display name, mapped name, truncated
// sender id, unknown label; group: mapped name, truncated sender id,
// group-member label, unknown label), trimming each candidate first.
// resolveChatDetailSenderName is the same call with the labels made
// explicit and isOwn defaulting to senderId == currentUserId.
// The contact display name itself falls back nickname -> name -> id,
// skipping blank parts; the sender id truncates to 8 units after trim.
// No fuzz test covered these functions before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files for ChatSenderDisplayName / senderDisplayName /
// resolveChatDetailSenderName / firstNonBlank).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
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

// The probed Kotlin whitespace set (fuzz-78): trim()/isBlank() treat
// exactly these units as blank; U+0085/U+200B/U+FEFF are not blank.
private fun isWs(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, in 0x1C..0x20 -> true
    0x00A0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
    in 0x2000..0x200A, in 0x2028..0x2029 -> true
    else -> false
}

private fun trimOracle(s: String): String = s.dropWhile(::isWs).dropLastWhile(::isWs)

private fun isBlankOracle(s: String): Boolean = s.all(::isWs)

private fun displayNameOracle(user: User): String =
    user.nickname?.takeIf { !isBlankOracle(it) }
        ?: user.name.takeIf { !isBlankOracle(it) }
        ?: user.id

private fun truncatedSenderIdOracle(senderId: String): String? {
    val id = trimOracle(senderId)
    if (id.isEmpty()) return null
    return if (id.length <= 8) id else id.take(8)
}

private fun firstNonBlankOracle(vararg values: String?): String? {
    for (value in values) {
        val trimmed = trimOracle(value.orEmpty())
        if (trimmed.isNotEmpty()) return trimmed
    }
    return null
}

private fun senderDisplayNameOracle(
    group: Boolean,
    isOwn: Boolean,
    contact: User,
    senderId: String,
    mapped: String?,
    unknownLabel: String,
    groupMemberLabel: String,
): String? {
    if (isOwn) return null
    val truncated = truncatedSenderIdOracle(senderId)
    if (!group) {
        return firstNonBlankOracle(displayNameOracle(contact), mapped, truncated, unknownLabel)
    }
    return firstNonBlankOracle(mapped, truncated, groupMemberLabel, unknownLabel)
}

private fun resolveChatDetailSenderNameOracle(
    group: Boolean,
    isOwn: Boolean,
    contact: User,
    senderId: String,
    mapped: String?,
    unknownLabel: String,
    groupMemberLabel: String,
): String? = senderDisplayNameOracle(
    group, isOwn, contact, senderId, mapped, unknownLabel, groupMemberLabel
)

// ---------- generators (mirrored by Oracle138 GEN, seed 138002) ----------

private val CSD_ID_POOL = arrayOf(
    "", " ", "me", "peer", "alice", "u1", "12345678", "123456789", "a b",
    "a\uD800b", "\uD83D\uDE00", "system", "x"
)

private val CSD_NAME_POOL = arrayOf(
    "", " ", "alice", "Alice", "bob", "   ", "\u00A0", "a b", "a\uD800b",
    "\uD83D\uDE00", "x", "\u0000", "12345678", "123456789", "Me", "peer"
)

private val CSD_NICK_POOL = arrayOf(
    "", " ", "Nick", "\u00A0", "a\uD800b", "\uD83D\uDE00"
)

private val CSD_LABEL_POOL = arrayOf(
    "", " ", "Unknown", "Member", "\u00A0", "x"
)

private fun genCsdId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CSD_ID_POOL[rnd.nextInt(CSD_ID_POOL.size)]
    else randomText(rnd, 10)

private fun genCsdName(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CSD_NAME_POOL[rnd.nextInt(CSD_NAME_POOL.size)]
    else randomText(rnd, 12)

private fun genCsdNick(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> CSD_NICK_POOL[rnd.nextInt(CSD_NICK_POOL.size)]
    2 -> "a".repeat(rnd.nextInt(0, 13))
    else -> randomText(rnd, 10)
}

private fun genCsdSenderId(rnd: Random): String = when (rnd.nextInt(3)) {
    0 -> CSD_ID_POOL[rnd.nextInt(CSD_ID_POOL.size)]
    1 -> randomText(rnd, 12)
    else -> "a".repeat(rnd.nextInt(0, 13))
}

private fun genCsdEntryName(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CSD_NAME_POOL[rnd.nextInt(CSD_NAME_POOL.size)]
    else randomText(rnd, 10)

private fun genCsdLabel(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CSD_LABEL_POOL[rnd.nextInt(CSD_LABEL_POOL.size)]
    else randomText(rnd, 8)

private data class CsdCase(
    val group: Boolean,
    val isOwn: Boolean,
    val contact: User,
    val currentUserId: String,
    val senderId: String,
    val entries: List<Pair<String, String>>,
    val unknownLabel: String,
    val groupMemberLabel: String,
)

private fun genCsdCase(rnd: Random): CsdCase {
    val group = rnd.nextBoolean()
    val isOwn = rnd.nextBoolean()
    val contact = User(
        id = genCsdId(rnd),
        name = genCsdName(rnd),
        nickname = genCsdNick(rnd),
    )
    val currentUserId = genCsdId(rnd)
    val senderId = genCsdSenderId(rnd)
    val nEntries = rnd.nextInt(0, 4)
    val entries = List(nEntries) { genCsdId(rnd) to genCsdEntryName(rnd) }
    val unknownLabel = genCsdLabel(rnd)
    val groupMemberLabel = genCsdLabel(rnd)
    return CsdCase(group, isOwn, contact, currentUserId, senderId, entries, unknownLabel, groupMemberLabel)
}

private fun CsdCase.state() = ChatDetailUiState(
    contact = contact,
    chatIsGroup = group,
    currentUserId = currentUserId,
)

private fun CsdCase.message() = Message(
    id = "m", chatId = "c", senderId = senderId, content = "x"
)

private fun CsdCase.mapped(): String? {
    val raw = entries.toMap()[senderId]
    return raw?.let { trimOracle(it) }?.takeIf { it.isNotEmpty() }
}

class ChatSenderDisplayNameFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Your own message never gets a sender label, in either function.
        val own = CsdCase(
            group = false, isOwn = true,
            contact = User(id = "peer", name = "Bob"),
            currentUserId = "me", senderId = "me",
            entries = listOf("me" to "Me"), unknownLabel = "", groupMemberLabel = "",
        )
        assertNull(senderDisplayName(own.state(), own.message(), true, own.entries.toMap()))
        assertNull(
            resolveChatDetailSenderName(
                own.message(), own.state(), own.entries.toMap(), "", "", true
            )
        )
        // Direct chat prefers the contact display name over the mapped one.
        val direct = CsdCase(
            group = false, isOwn = false,
            contact = User(id = "peer", name = "Bob"),
            currentUserId = "me", senderId = "peer",
            entries = listOf("peer" to "Mapped"), unknownLabel = "?", groupMemberLabel = "",
        )
        assertEquals("Bob", senderDisplayName(direct.state(), direct.message(), false, direct.entries.toMap()))
        // Blank contact (empty id and name) falls through to the mapped name.
        val blankContact = CsdCase(
            group = false, isOwn = false,
            contact = User(id = "", name = ""),
            currentUserId = "me", senderId = "peer",
            entries = listOf("peer" to "Mapped"), unknownLabel = "?", groupMemberLabel = "",
        )
        assertEquals("Mapped", senderDisplayName(blankContact.state(), blankContact.message(), false, blankContact.entries.toMap()))
        // Group chat ignores the contact name and takes the mapped name.
        val group = CsdCase(
            group = true, isOwn = false,
            contact = User(id = "peer", name = "Bob"),
            currentUserId = "me", senderId = "peer",
            entries = listOf("peer" to "Mapped"), unknownLabel = "?", groupMemberLabel = "Member",
        )
        assertEquals("Mapped", senderDisplayName(group.state(), group.message(), false, group.entries.toMap()))
        // Blank mapped name falls through to the truncated sender id (8 units).
        val trunc = CsdCase(
            group = true, isOwn = false,
            contact = User(id = "peer", name = "Bob"),
            currentUserId = "me", senderId = "123456789",
            entries = listOf("123456789" to "   "), unknownLabel = "?", groupMemberLabel = "Member",
        )
        assertEquals("12345678", senderDisplayName(trunc.state(), trunc.message(), false, trunc.entries.toMap()))
        // An 8-unit sender id passes through whole; a 9-unit one is cut.
        val eight = CsdCase(
            group = true, isOwn = false,
            contact = User(id = "", name = ""),
            currentUserId = "me", senderId = "12345678",
            entries = emptyList(), unknownLabel = "", groupMemberLabel = "",
        )
        assertEquals("12345678", senderDisplayName(eight.state(), eight.message(), false, emptyMap()))
        // Everything blank lands on the unknown label; in a group the
        // member label wins over the unknown label.
        val unknown = CsdCase(
            group = false, isOwn = false,
            contact = User(id = "", name = ""),
            currentUserId = "me", senderId = "  ",
            entries = emptyList(), unknownLabel = "?", groupMemberLabel = "",
        )
        assertEquals("?", senderDisplayName(unknown.state(), unknown.message(), false, emptyMap(), "?", ""))
        val member = unknown.copy(group = true, groupMemberLabel = "Member")
        assertEquals("Member", senderDisplayName(member.state(), member.message(), false, emptyMap(), "?", "Member"))
        // resolveChatDetailSenderName defaults isOwn to senderId == currentUserId.
        val self = direct.copy(senderId = "me")
        assertNull(
            resolveChatDetailSenderName(
                self.message(), self.state(), self.entries.toMap(), "?", ""
            )
        )
        assertEquals(
            "Bob",
            resolveChatDetailSenderName(
                direct.message(), direct.state(), direct.entries.toMap(), "?", ""
            )
        )
        // Nickname beats name, blank nickname falls back to name, blank
        // name falls back to id.
        val nick = CsdCase(
            group = false, isOwn = false,
            contact = User(id = "peer", name = "Bob", nickname = "Bobby"),
            currentUserId = "me", senderId = "peer",
            entries = emptyList(), unknownLabel = "?", groupMemberLabel = "",
        )
        assertEquals("Bobby", senderDisplayName(nick.state(), nick.message(), false, emptyMap()))
        val blankNick = nick.copy(contact = User(id = "peer", name = "Bob", nickname = "  "))
        assertEquals("Bob", senderDisplayName(blankNick.state(), blankNick.message(), false, emptyMap()))
        val blankName = nick.copy(contact = User(id = "peer", name = "  ", nickname = null))
        assertEquals("peer", senderDisplayName(blankName.state(), blankName.message(), false, emptyMap()))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle138.genCsd consumption order exactly, so seed
        // 138002 replays the same 1500 GEN lines drive138.py ran.
        val rnd = Random(138002)
        repeat(1500) {
            val c = genCsdCase(rnd)
            val state = c.state()
            val msg = c.message()
            val map = c.entries.toMap()
            val mapped = c.mapped()
            val want = senderDisplayNameOracle(
                c.group, c.isOwn, c.contact, c.senderId, mapped, c.unknownLabel, c.groupMemberLabel
            )
            assertEquals(
                want,
                senderDisplayName(
                    state = state, message = msg, isOwn = c.isOwn,
                    participantNamesById = map,
                    unknownLabel = c.unknownLabel, groupMemberLabel = c.groupMemberLabel,
                )
            )
            assertEquals(
                want,
                resolveChatDetailSenderName(
                    message = msg, state = state, participantNamesById = map,
                    unknownLabel = c.unknownLabel, groupMemberLabel = c.groupMemberLabel,
                    isOwn = c.isOwn,
                )
            )
            assertEquals(
                senderDisplayNameOracle(
                    c.group, c.senderId == c.currentUserId, c.contact, c.senderId,
                    mapped, c.unknownLabel, c.groupMemberLabel
                ),
                resolveChatDetailSenderName(
                    message = msg, state = state, participantNamesById = map,
                    unknownLabel = c.unknownLabel, groupMemberLabel = c.groupMemberLabel,
                )
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Contact display-name fallback matrix: nickname x name x id.
        val nicks: List<String?> = listOf(null, "", " ", "Nick", "\u00A0")
        val names = listOf("", " ", "Bob")
        val ids = listOf("", "peer", "123456789")
        for (nick in nicks) {
            for (name in names) {
                for (id in ids) {
                    for (group in listOf(false, true)) {
                        for (isOwn in listOf(false, true)) {
                            val c = CsdCase(
                                group = group, isOwn = isOwn,
                                contact = User(id = id, name = name, nickname = nick),
                                currentUserId = "me", senderId = "peer",
                                entries = listOf("peer" to "Mapped"),
                                unknownLabel = "?", groupMemberLabel = "Member",
                            )
                            val want = senderDisplayNameOracle(
                                group, isOwn, c.contact, "peer", "Mapped", "?", "Member"
                            )
                            assertEquals(
                                want,
                                senderDisplayName(c.state(), c.message(), isOwn, c.entries.toMap(), "?", "Member")
                            )
                        }
                    }
                }
            }
        }
        // Sender-id length sweep: 0..12 "a"s, no mapping, no labels.
        for (n in 0..12) {
            val senderId = "a".repeat(n)
            val c = CsdCase(
                group = true, isOwn = false,
                contact = User(id = "", name = ""),
                currentUserId = "me", senderId = senderId,
                entries = emptyList(), unknownLabel = "", groupMemberLabel = "",
            )
            assertEquals(
                truncatedSenderIdOracle(senderId),
                senderDisplayName(c.state(), c.message(), false, emptyMap())
            )
        }
        // Every single low unit as a 1-char sender id / contact name /
        // unknown label / group-member label / mapped name.
        for (code in 0x00..0x7F) {
            val ch = code.toChar().toString()
            val base = CsdCase(
                group = true, isOwn = false,
                contact = User(id = "", name = ""),
                currentUserId = "me", senderId = "peer",
                entries = listOf("peer" to "Mapped"),
                unknownLabel = "?", groupMemberLabel = "Member",
            )
            val bySender = base.copy(senderId = ch, entries = emptyList())
            assertEquals(
                senderDisplayNameOracle(true, false, bySender.contact, ch, null, "?", "Member"),
                senderDisplayName(bySender.state(), bySender.message(), false, emptyMap(), "?", "Member")
            )
            val byName = base.copy(contact = User(id = "", name = ch))
            assertEquals(
                senderDisplayNameOracle(true, false, byName.contact, "peer", "Mapped", "?", "Member"),
                senderDisplayName(byName.state(), byName.message(), false, byName.entries.toMap(), "?", "Member")
            )
            val byUnknown = base.copy(entries = emptyList(), unknownLabel = ch)
            assertEquals(
                senderDisplayNameOracle(true, false, byUnknown.contact, "peer", null, ch, "Member"),
                senderDisplayName(byUnknown.state(), byUnknown.message(), false, emptyMap(), ch, "Member")
            )
            val byMapped = base.copy(entries = listOf("peer" to ch))
            assertEquals(
                senderDisplayNameOracle(true, false, byMapped.contact, "peer", trimOracle(ch).takeIf { it.isNotEmpty() }, "?", "Member"),
                senderDisplayName(byMapped.state(), byMapped.message(), false, byMapped.entries.toMap(), "?", "Member")
            )
        }
    }
}
