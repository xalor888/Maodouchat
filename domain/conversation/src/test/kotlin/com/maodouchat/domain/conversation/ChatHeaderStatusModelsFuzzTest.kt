// Fuzz / random-oracle tests for ChatHeaderStatusModels.resolveChatHeaderStatus (checklist line 931,
// 119th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. resolveChatHeaderStatus: typing > online > custom > last-seen > offline / none.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHeaderStatusModelsFuzzTest {

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
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
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

// ---------- block-119 common codec helpers ----------

// Local hex encoder (same codec as Parse119.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse119.kt, so the codec lives here.
private fun encSpecStr(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun encNullableSpecStr(s: String?): String =
    if (s == null) "N" else "S" + encSpecStr(s)

private fun decSpecStr(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4)
    for (i in chars.indices) {
        chars[i] = h.substring(i * 4, i * 4 + 4).toInt(16).toChar()
    }
    return String(chars)
}

private fun decNullableSpecStr(f: String): String? =
    if (f == "N") null else decSpecStr(f.substring(1))

// Id-set spec (shared by the CMI and CLS clusters): ids joined by
// ";" with each id hex-encoded; empty set is "". Iteration order is
// the LinkedHashSet insertion order on both sides.
private fun idSetSpecOf(set: Set<String>): String =
    set.joinToString(";") { encSpecStr(it) }

private fun idSetOfSpec(spec: String): Set<String> =
    if (spec.isEmpty()) emptySet()
    else spec.split(";").map { decSpecStr(it) }.toSet()

// Hand-written lastIndexOf over UTF-16 units. Never lastIndexOf().
private fun lastIndexOfOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return s.length
    if (needle.length > s.length) return -1
    var i = s.length - needle.length
    while (i >= 0) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return i
        i--
    }
    return -1
}

// Case-insensitive startsWith, per-char fold like
// regionMatches(ignoreCase = true). Exact for every input.
private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

// ---------- ChatHeaderStatusModels oracle ----------

private fun chsStatusOracle(
    typingUserId: String?,
    isOnline: Boolean,
    customStatus: String,
    isGroup: Boolean,
    lastSeen: Long
): ChatHeaderStatus {
    val activeTypingUserId = typingUserId?.let { trimOracle(it) } ?: ""
    return when {
        activeTypingUserId.isNotEmpty() -> ChatHeaderStatus.Typing(activeTypingUserId)
        isOnline -> ChatHeaderStatus.Online
        !isBlankOracle(customStatus) -> ChatHeaderStatus.Custom(customStatus)
        !isGroup && lastSeen > 0L -> ChatHeaderStatus.LastSeen(lastSeen)
        !isGroup -> ChatHeaderStatus.Offline
        else -> ChatHeaderStatus.None
    }
}

private fun chsStatusEncode(status: ChatHeaderStatus): String = when (status) {
    is ChatHeaderStatus.Typing -> "TYPING|" + encSpecStr(status.userId)
    ChatHeaderStatus.Online -> "ONLINE"
    ChatHeaderStatus.Offline -> "OFFLINE"
    is ChatHeaderStatus.LastSeen -> "LASTSEEN|" + status.timestamp.toString()
    is ChatHeaderStatus.Custom -> "CUSTOM|" + encSpecStr(status.text)
    ChatHeaderStatus.None -> "NONE"
}

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- ChatHeaderStatusModels GEN ----------

private val CHS_TYPING_POOL119 = arrayOf(
    "", " ", "  ", "\u00A0", "u1", "  u1  ", "\t", "u2", "😀"
)

private val CHS_CUSTOM_POOL119 = arrayOf(
    "", " ", "  ", "\u00A0", "busy", "in a meeting", "😀", "\t"
)

private val CHS_SEEN_POOL119 = longArrayOf(
    0L, 1L, -1L, 1000L, 1700000000000L, 9223372036854775807L, Long.MIN_VALUE
)

private fun genChsTyping119(rnd: Random, poolOnly: Boolean): String? =
    when {
        poolOnly -> if (rnd.nextInt(8) == 0) null
            else CHS_TYPING_POOL119[rnd.nextInt(CHS_TYPING_POOL119.size)]
        rnd.nextInt(8) == 0 -> null
        rnd.nextInt(3) == 0 -> CHS_TYPING_POOL119[rnd.nextInt(CHS_TYPING_POOL119.size)]
        else -> randomToken(rnd)
    }

private fun genChsCustom119(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly || rnd.nextInt(3) == 0) CHS_CUSTOM_POOL119[rnd.nextInt(CHS_CUSTOM_POOL119.size)]
    else randomString(rnd, 12)

private fun genChsLastSeen119(rnd: Random, poolOnly: Boolean): Long =
    if (poolOnly) CHS_SEEN_POOL119[rnd.nextInt(CHS_SEEN_POOL119.size)]
    else rnd.nextLong()

    @Test
    fun chatHeaderStatusModelsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val typing = genChsTyping119(rnd, poolOnly = false)
            val online = rnd.nextBoolean()
            val custom = genChsCustom119(rnd, poolOnly = false)
            val group = rnd.nextBoolean()
            val seen = genChsLastSeen119(rnd, poolOnly = false)
            assertEquals(
                chsStatusOracle(typing, online, custom, group, seen),
                resolveChatHeaderStatus(typing, online, custom, group, seen)
            )
        }
    }

    @Test
    fun chatHeaderStatusModelsEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val typing = genChsTyping119(rnd, poolOnly = true)
            val online = rnd.nextBoolean()
            val custom = genChsCustom119(rnd, poolOnly = true)
            val group = rnd.nextBoolean()
            val seen = genChsLastSeen119(rnd, poolOnly = true)
            assertEquals(
                chsStatusOracle(typing, online, custom, group, seen),
                resolveChatHeaderStatus(typing, online, custom, group, seen)
            )
        }
    }

    @Test
    fun chatHeaderStatusModelsPinned() {
        // Typing wins over everything, but the id is trimmed first.
        assertEquals(
            ChatHeaderStatus.Typing("u1"),
            resolveChatHeaderStatus("  u1  ", true, "busy", false, 999L)
        )
        assertEquals(
            ChatHeaderStatus.Typing("u1"),
            chsStatusOracle("  u1  ", true, "busy", false, 999L)
        )
        // Blank typing id falls through (whitespace-only, incl. NBSP).
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus("  \u00A0 ", true, "", false, 0L)
        )
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus(null, true, "busy", false, 0L)
        )
        // Custom status needs non-blank text; blank custom falls through.
        assertEquals(
            ChatHeaderStatus.Custom("busy"),
            resolveChatHeaderStatus(null, false, "busy", false, 0L)
        )
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, false, "   ", false, 0L)
        )
        // Last seen only for 1:1 chats and only when > 0.
        assertEquals(
            ChatHeaderStatus.LastSeen(999L),
            resolveChatHeaderStatus(null, false, "", false, 999L)
        )
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, false, "", false, 0L)
        )
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, false, "", false, -5L)
        )
        // Groups never show Offline / LastSeen.
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, false, "", true, 999L)
        )
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, false, "", true, 0L)
        )
        // Typing in a group still wins.
        assertEquals(
            ChatHeaderStatus.Typing("u2"),
            resolveChatHeaderStatus("u2", false, "", true, 0L)
        )
        // Custom beats last-seen even for 1:1.
        assertEquals(
            ChatHeaderStatus.Custom("away"),
            resolveChatHeaderStatus(null, false, "away", false, 999L)
        )
        assertEquals(
            "TYPING|" + encSpecStr("u1"),
            chsStatusEncode(ChatHeaderStatus.Typing("u1"))
        )
        assertEquals("NONE", chsStatusEncode(ChatHeaderStatus.None))
        assertEquals(
            "LASTSEEN|999",
            chsStatusEncode(ChatHeaderStatus.LastSeen(999L))
        )
    }
}
