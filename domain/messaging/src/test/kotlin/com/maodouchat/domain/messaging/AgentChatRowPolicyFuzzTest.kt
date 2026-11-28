// Fuzz / random-oracle tests for AgentChatRowPolicy (checklist line 931,
// 71st block). Test-only, zero production changes. The oracle is
// hand-written: trim / blank check over the probed 28-unit Kotlin
// whitespace set, unit-by-unit equality, hand-written take (UTF-16
// units), per-code-point simple lowercase, hand-written contains,
// explicit membership loops. chatListRow is a plain tab join; chatDetail is 9 appendLine lines then trim(), so trailing whitespace inside lastPreview is stripped and a wholly-blank lastPreview leaves a bare last= line.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentChatRowPolicyFuzzTest {

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

// ---------- AgentChatRowPolicy oracle ----------

private fun rowListOracle(id: String, title: String, lastPreview: String, flags: String): String =
    id + "\t" + title + "\t" + lastPreview + "\t" + flags

private fun rowDetailOracle(
    id: String,
    title: String,
    chatType: String,
    muted: Boolean,
    pinned: Boolean,
    archived: Boolean,
    unread: Int,
    markedUnread: Boolean,
    lastPreview: String
): String {
    val sb = StringBuilder()
    sb.append("id=").append(id).append('\n')
    sb.append("title=").append(title).append('\n')
    sb.append("type=").append(chatType).append('\n')
    sb.append("muted=").append(muted).append('\n')
    sb.append("pinned=").append(pinned).append('\n')
    sb.append("archived=").append(archived).append('\n')
    sb.append("unread=").append(unread).append('\n')
    sb.append("markedUnread=").append(markedUnread).append('\n')
    sb.append("last=").append(lastPreview).append('\n')
    return trimOracle(sb.toString())
}

private fun genRowField(rnd: Random): String =
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

private fun genRowType(rnd: Random): String =
    when (rnd.nextInt(6)) {
        0 -> "GROUP"
        1 -> "PRIVATE"
        2 -> "CHANNEL"
        3 -> ""
        4 -> "group"
        else -> randomToken(rnd)
    }

private fun genRowUnread(rnd: Random): Int =
    when (rnd.nextInt(8)) {
        0 -> 0
        1 -> 1
        2 -> -1
        3 -> rnd.nextInt(-100, 100)
        4 -> Int.MIN_VALUE
        5 -> Int.MAX_VALUE
        else -> rnd.nextInt()
    }

    @Test
    fun rowsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val id = genRowField(rnd)
            val title = genRowField(rnd)
            val preview = genRowField(rnd)
            val flags = genRowField(rnd)
            assertEquals(rowListOracle(id, title, preview, flags), AgentChatRowPolicy.chatListRow(id, title, preview, flags))
            val chatType = genRowType(rnd)
            val muted = rnd.nextBoolean()
            val pinned = rnd.nextBoolean()
            val archived = rnd.nextBoolean()
            val unread = genRowUnread(rnd)
            val markedUnread = rnd.nextBoolean()
            assertEquals(
                rowDetailOracle(id, title, chatType, muted, pinned, archived, unread, markedUnread, preview),
                AgentChatRowPolicy.chatDetail(id, title, chatType, muted, pinned, archived, unread, markedUnread, preview)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        assertEquals(
            "c1\tFamily\tHello\tgroup,unread=2",
            AgentChatRowPolicy.chatListRow("c1", "Family", "Hello", "group,unread=2")
        )
        assertEquals("c2\tBob\t\t", AgentChatRowPolicy.chatListRow("c2", "Bob", "", ""))
        // Tabs and newlines inside fields are preserved by the tab join.
        assertEquals("a\tb\tc\td", AgentChatRowPolicy.chatListRow("a", "b", "c", "d"))
        assertEquals("x\ty\t\n\t\t", AgentChatRowPolicy.chatListRow("x\ty", "\n", "", ""))
        // chatDetail renders nine lines in fixed order.
        assertEquals(
            "id=c1\ntitle=Family\ntype=GROUP\nmuted=true\npinned=true\narchived=false\nunread=3\nmarkedUnread=true\nlast=Hello",
            AgentChatRowPolicy.chatDetail(
                id = "c1", title = "Family", chatType = "GROUP",
                muted = true, pinned = true, archived = false,
                unread = 3, markedUnread = true, lastPreview = "Hello"
            )
        )
        // chatDetail trims: trailing whitespace inside lastPreview is stripped.
        assertEquals(
            "id=c2\ntitle=Bob\ntype=PRIVATE\nmuted=false\npinned=false\narchived=false\nunread=0\nmarkedUnread=false\nlast=Hello",
            AgentChatRowPolicy.chatDetail(
                id = "c2", title = "Bob", chatType = "PRIVATE",
                muted = false, pinned = false, archived = false,
                unread = 0, markedUnread = false, lastPreview = "Hello   "
            )
        )
        // A wholly-blank lastPreview leaves a bare "last=" line.
        assertEquals(
            "id=c2\ntitle=Bob\ntype=PRIVATE\nmuted=false\npinned=false\narchived=false\nunread=0\nmarkedUnread=false\nlast=",
            AgentChatRowPolicy.chatDetail(
                id = "c2", title = "Bob", chatType = "PRIVATE",
                muted = false, pinned = false, archived = false,
                unread = 0, markedUnread = false, lastPreview = "   "
            )
        )
        // Negative unread renders with a minus sign, like any Int.
        assertEquals(
            "id=c3\ntitle=X\ntype=GROUP\nmuted=false\npinned=false\narchived=false\nunread=-5\nmarkedUnread=false\nlast=",
            AgentChatRowPolicy.chatDetail(
                id = "c3", title = "X", chatType = "GROUP",
                muted = false, pinned = false, archived = false,
                unread = -5, markedUnread = false, lastPreview = ""
            )
        )
    }
}
