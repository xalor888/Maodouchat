// Fuzz / random-oracle tests for AgentGroupResultPolicy (checklist line 931,
// 74th block). Test-only, zero production changes. The oracle is
// hand-written: plain string building with the exact templates
// read from the production file. directChat / group / renamed / announcementUpdated are chat templates; membersAdded carries an Int count verbatim, muted carries a Long until verbatim.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentGroupResultPolicyFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E9A, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- AgentGroupResultPolicy oracle ----------

private fun groupDirectChatOracle(id: String): String =
    "Direct chat " + id

private fun groupGroupOracle(id: String, name: String): String =
    "Group " + id + " name=" + name

private fun groupRenamedOracle(chatId: String, name: String): String =
    "Renamed " + chatId + " to " + name

private fun groupAnnouncementOracle(chatId: String): String =
    "Updated announcement for " + chatId

private fun groupMembersAddedOracle(count: Int, chatId: String): String =
    "Added " + count + " members to " + chatId

private fun groupMemberRemovedOracle(memberId: String, chatId: String): String =
    "Removed " + memberId + " from " + chatId

private fun groupMutedOracle(memberId: String, mutedUntil: Long): String =
    "Muted " + memberId + " until " + mutedUntil

private fun groupChatDeletedOracle(chatId: String): String =
    "Deleted chat " + chatId

private fun genGroupField(rnd: Random): String =
    when (rnd.nextInt(8)) {
        0 -> ""
        1 -> " "
        2 -> "  x  "
        3 -> "x\ty"
        4 -> "x\ny"
        5 -> randomWord(rnd)
        6 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genGroupName(rnd: Random): String =
    when (rnd.nextInt(6)) {
        0 -> ""
        1 -> "毛豆群"
        2 -> "  x  "
        3 -> randomWord(rnd)
        4 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genGroupCount(rnd: Random): Int =
    when (rnd.nextInt(8)) {
        0 -> 0
        1 -> 1
        2 -> 2
        3 -> -1
        4 -> Int.MIN_VALUE
        5 -> Int.MAX_VALUE
        6 -> rnd.nextInt(-100, 100)
        else -> rnd.nextInt()
    }

private fun genGroupUntil(rnd: Random): Long =
    when (rnd.nextInt(8)) {
        0 -> 0L
        1 -> 1L
        2 -> -1L
        3 -> 1700000000000L
        4 -> Long.MIN_VALUE
        5 -> Long.MAX_VALUE
        6 -> rnd.nextLong(-100000L, 100000L)
        else -> rnd.nextLong()
    }

    @Test
    fun groupChatRandom() {
        val rnd = Random(42)
        repeat(300) {
            val id = genGroupField(rnd)
            val name = genGroupName(rnd)
            assertEquals(
                groupDirectChatOracle(id),
                AgentGroupResultPolicy.directChat(id)
            )
            assertEquals(
                groupGroupOracle(id, name),
                AgentGroupResultPolicy.group(id, name)
            )
            assertEquals(
                groupRenamedOracle(id, name),
                AgentGroupResultPolicy.renamed(id, name)
            )
            assertEquals(
                groupAnnouncementOracle(id),
                AgentGroupResultPolicy.announcementUpdated(id)
            )
        }
    }

    @Test
    fun groupMemberRandom() {
        val rnd = Random(42)
        repeat(300) {
            val memberId = genGroupField(rnd)
            val chatId = genGroupField(rnd)
            val count = genGroupCount(rnd)
            val until = genGroupUntil(rnd)
            assertEquals(
                groupMembersAddedOracle(count, chatId),
                AgentGroupResultPolicy.membersAdded(count, chatId)
            )
            assertEquals(
                groupMemberRemovedOracle(memberId, chatId),
                AgentGroupResultPolicy.memberRemoved(memberId, chatId)
            )
            assertEquals(
                groupMutedOracle(memberId, until),
                AgentGroupResultPolicy.muted(memberId, until)
            )
            assertEquals(
                groupChatDeletedOracle(chatId),
                AgentGroupResultPolicy.chatDeleted(chatId)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        assertEquals(
            "Direct chat c1",
            AgentGroupResultPolicy.directChat("c1")
        )
        assertEquals(
            "Group g1 name=毛豆群",
            AgentGroupResultPolicy.group("g1", "毛豆群")
        )
        assertEquals(
            "Renamed c1 to 新群名",
            AgentGroupResultPolicy.renamed("c1", "新群名")
        )
        assertEquals(
            "Updated announcement for c1",
            AgentGroupResultPolicy.announcementUpdated("c1")
        )
        assertEquals(
            "Added 3 members to c1",
            AgentGroupResultPolicy.membersAdded(3, "c1")
        )
        // Negative and extreme counts render verbatim, no clamp.
        assertEquals(
            "Added -1 members to c1",
            AgentGroupResultPolicy.membersAdded(-1, "c1")
        )
        assertEquals(
            "Added -2147483648 members to c1",
            AgentGroupResultPolicy.membersAdded(Int.MIN_VALUE, "c1")
        )
        assertEquals(
            "Added 2147483647 members to c1",
            AgentGroupResultPolicy.membersAdded(Int.MAX_VALUE, "c1")
        )
        assertEquals(
            "Removed u2 from c1",
            AgentGroupResultPolicy.memberRemoved("u2", "c1")
        )
        assertEquals(
            "Muted u2 until 1700000000000",
            AgentGroupResultPolicy.muted("u2", 1700000000000L)
        )
        // Negative and extreme mute-until millis render verbatim.
        assertEquals(
            "Muted u2 until -9223372036854775808",
            AgentGroupResultPolicy.muted("u2", Long.MIN_VALUE)
        )
        assertEquals(
            "Muted u2 until 9223372036854775807",
            AgentGroupResultPolicy.muted("u2", Long.MAX_VALUE)
        )
        assertEquals(
            "Deleted chat c1",
            AgentGroupResultPolicy.chatDeleted("c1")
        )
        // Empty ids keep the template shape.
        assertEquals(
            "Group  name=",
            AgentGroupResultPolicy.group("", "")
        )
    }
}
