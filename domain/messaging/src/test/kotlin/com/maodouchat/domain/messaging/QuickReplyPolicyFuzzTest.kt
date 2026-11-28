// Fuzz / random-oracle tests for QuickReplyPolicy (checklist line 931,
// 70th block). Test-only, zero production changes. The oracle is
// hand-written: trim / blank check over the probed 28-unit Kotlin
// whitespace set, unit-by-unit equality, hand-written take (UTF-16
// units), explicit membership loops, hand-written dropLastWhile /
// filter loops. validate checks isNotBlank on both id and text and text.length <= 4000 in UTF-16 units; sanitizeReplyText trims then takes 2000 (the widget limit, verified, not 4000); dedupeKey is owner|chat| + the first 8 bytes of SHA-256 as hex.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import com.maodouchat.core.model.ConversationWidgetContract

class QuickReplyPolicyFuzzTest {

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

// ---------- QuickReplyPolicy oracle ----------
// MAX_REPLY_LENGTH verified = 2000 (ConversationWidgetContract.kt:30).

private fun quickValidateOracle(conversationId: String, text: String): Boolean =
    !isBlankOracle(conversationId) && !isBlankOracle(text) && text.length <= 4000

private fun quickSanitizeOracle(raw: String?): String =
    takeOracle(trimOracle(raw ?: ""), 2000)

private fun quickDedupeKeyOracle(ownerUserId: String, chatId: String, text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(16)
    for (i in 0 until 8) sb.append("%02x".format(digest[i]))
    return ownerUserId + "|" + chatId + "|" + sb.toString()
}

private fun genQuickConversationId(rnd: Random): String {
    return when (rnd.nextInt(8)) {
        0 -> "c1"
        1 -> ""
        2 -> "   "
        3 -> "\t"
        4 -> " c1 "
        5 -> "\nc1\t"
        6 -> randomWord(rnd)
        else -> randomString(rnd, 20)
    }
}

private fun genQuickText(rnd: Random): String {
    return when (rnd.nextInt(12)) {
        0 -> ""
        1 -> "   "
        2 -> "\n"
        3 -> " \n\t "
        4 -> "hi"
        5 -> randomWord(rnd)
        6 -> "a".repeat(rnd.nextInt(3990, 4011))
        7 -> "😀".repeat(rnd.nextInt(1995, 2006))
        8 -> " ".repeat(rnd.nextInt(0, 50)) + randomWord(rnd) + " ".repeat(rnd.nextInt(0, 50))
        9 -> randomString(rnd, 120)
        10 -> randomString(rnd, 4500)
        else -> randomString(rnd, 400)
    }
}

private fun genQuickRaw(rnd: Random): String? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> ""
        2 -> "   "
        3 -> "  hello  "
        4 -> randomWord(rnd)
        5 -> "a".repeat(rnd.nextInt(1990, 2011))
        6 -> "😀".repeat(rnd.nextInt(995, 1006))
        7 -> " ".repeat(rnd.nextInt(0, 30)) + randomWord(rnd) + " ".repeat(rnd.nextInt(0, 30))
        8 -> randomString(rnd, 120)
        else -> randomString(rnd, 2500)
    }
}

private fun genQuickDedupe(rnd: Random): Triple<String, String, String> {
    val owner = when (rnd.nextInt(6)) {
        0 -> "owner"
        1 -> ""
        2 -> "a|b"
        3 -> randomWord(rnd)
        4 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }
    val chat = when (rnd.nextInt(6)) {
        0 -> "chat"
        1 -> ""
        2 -> "x|y"
        3 -> randomWord(rnd)
        4 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }
    val text = when (rnd.nextInt(8)) {
        0 -> "hello"
        1 -> ""
        2 -> "hello!"
        3 -> "😀"
        4 -> randomWord(rnd)
        5 -> randomString(rnd, 60)
        6 -> "a".repeat(rnd.nextInt(0, 300))
        else -> randomString(rnd, 200)
    }
    return Triple(owner, chat, text)
}

    @Test
    fun validateRandom() {
        val rnd = Random(42)
        repeat(300) {
            val conversationId = genQuickConversationId(rnd)
            val text = genQuickText(rnd)
            assertEquals(quickValidateOracle(conversationId, text), QuickReplyPolicy.validate(conversationId, text))
        }
    }

    @Test
    fun sanitizeAndDedupeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = genQuickRaw(rnd)
            assertEquals(quickSanitizeOracle(raw), QuickReplyPolicy.sanitizeReplyText(raw))
            val (owner, chat, text) = genQuickDedupe(rnd)
            assertEquals(quickDedupeKeyOracle(owner, chat, text), QuickReplyPolicy.dedupeKey(owner, chat, text))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // validate pinned: blank on either side fails, length is UTF-16 units.
        assertEquals(true, QuickReplyPolicy.validate("c1", "hi"))
        assertEquals(false, QuickReplyPolicy.validate("", "hi"))
        assertEquals(false, QuickReplyPolicy.validate("c1", "  "))
        assertEquals(false, QuickReplyPolicy.validate("   ", "hi"))
        assertEquals(false, QuickReplyPolicy.validate("\t", "hi"))
        assertEquals(false, QuickReplyPolicy.validate("c1", "\n"))
        assertEquals(false, QuickReplyPolicy.validate("c1", ""))
        assertEquals(true, QuickReplyPolicy.validate(" c1 ", "hi"))
        assertEquals(true, QuickReplyPolicy.validate("c1", "  hi\n"))
        assertEquals(true, QuickReplyPolicy.validate("c1", "x".repeat(4000)))
        assertEquals(false, QuickReplyPolicy.validate("c1", "x".repeat(4001)))
        assertEquals(true, QuickReplyPolicy.validate("c1", "中".repeat(4000)))
        assertEquals(false, QuickReplyPolicy.validate("c1", "中".repeat(4001)))
        assertEquals(true, QuickReplyPolicy.validate("c1", "😀".repeat(2000)))
        assertEquals(false, QuickReplyPolicy.validate("c1", "😀".repeat(2001)))
        assertEquals(4000, QuickReplyPolicy.MAX_TEXT_LENGTH)
        // A 4000-char blank string fails the blank check, not the length check.
        assertEquals(false, QuickReplyPolicy.validate("c1", " ".repeat(4000)))
        assertEquals(true, QuickReplyPolicy.validate("c1", "x".repeat(3999) + "\n"))

        // sanitizeReplyText pinned: trim, then cap at the widget limit 2000 (not 4000).
        assertEquals("hello", QuickReplyPolicy.sanitizeReplyText("  hello  "))
        assertEquals("", QuickReplyPolicy.sanitizeReplyText(null))
        assertEquals("", QuickReplyPolicy.sanitizeReplyText("   "))
        assertEquals(2000, QuickReplyPolicy.sanitizeReplyText("x".repeat(2010)).length)
        assertEquals("x".repeat(2000), QuickReplyPolicy.sanitizeReplyText("x".repeat(2010)))
        assertEquals(2000, ConversationWidgetContract.MAX_REPLY_LENGTH)
        assertEquals("x".repeat(2000), QuickReplyPolicy.sanitizeReplyText("  " + "x".repeat(2010) + "  "))
        assertEquals("😀".repeat(1000), QuickReplyPolicy.sanitizeReplyText("😀".repeat(1001)))
        assertEquals("", QuickReplyPolicy.sanitizeReplyText("😀".repeat(0)))

        // dedupeKey pinned: known SHA-256 vector (hashlib-verified).
        assertEquals("owner|chat|2cf24dba5fb0a30e", QuickReplyPolicy.dedupeKey("owner", "chat", "hello"))
        assertEquals(QuickReplyPolicy.dedupeKey("owner", "chat", "hello"), QuickReplyPolicy.dedupeKey("owner", "chat", "hello"))
        assertEquals(true, QuickReplyPolicy.dedupeKey("owner", "chat", "hello").startsWith("owner|chat|"))
        assertEquals(16, QuickReplyPolicy.dedupeKey("owner", "chat", "hello").substringAfterLast("|").length)
        assertEquals(false, QuickReplyPolicy.dedupeKey("owner", "chat", "hello") == QuickReplyPolicy.dedupeKey("owner", "chat", "hello!"))
        assertEquals(false, QuickReplyPolicy.dedupeKey("owner", "chat", "hello") == QuickReplyPolicy.dedupeKey("other", "chat", "hello"))
        assertEquals(false, QuickReplyPolicy.dedupeKey("owner", "chat", "hello") == QuickReplyPolicy.dedupeKey("owner", "other", "hello"))
        assertEquals("||e3b0c44298fc1c14", QuickReplyPolicy.dedupeKey("", "", ""))
    }
}
