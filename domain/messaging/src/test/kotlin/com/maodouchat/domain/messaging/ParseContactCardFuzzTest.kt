// Fuzz / random-oracle tests for ParseContactCard (checklist line 931,
// 97th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. AttachmentIntentPolicy.parseContactCard strips the scheme, takes name= from the query, falls back to the caption, then to "contact".
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ParseContactCardFuzzTest {

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

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun indexOfCharOracle(s: String, ch: Char): Int {
    for (i in s.indices) {
        if (s[i] == ch) return i
    }
    return -1
}

// Java String.hashCode(): h = 31*h + unit, Int arithmetic wraps.
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// java.lang.Long.parseLong(s, radix), reimplemented with the same
// JDK primitive (Character.digit) and the same negative-accumulator
// overflow algorithm, so the exact Long.MIN_VALUE edge parses.
private fun parseLongRadixOracle(s: String, radix: Int): Long? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0L
    val limit = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
    val multmin = limit / radix
    while (i < s.length) {
        val d = Character.digit(s[i].code, radix)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= radix
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

// java.lang.Integer.parseInt(s), same algorithm on Int.
private fun parseIntOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0
    val limit = if (neg) Int.MIN_VALUE else -Int.MAX_VALUE
    val multmin = limit / 10
    while (i < s.length) {
        val d = Character.digit(s[i].code, 10)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= 10
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

private fun isDigitOracle(c: Char): Boolean = Character.isDigit(c.code)


// ---------- extra helpers, block 80 ----------

// Per-code-point simple case mapping, mirroring lowercaseOracle.
// Agrees with String.uppercase() (full mapping) on the case-safe
// alphabet; never calls uppercase().
private fun uppercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toUpperCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// String.substringBefore(delimiter): up to the first occurrence,
// or the whole string when absent.
private fun substringBeforeOracle(s: String, delim: Char): String {
    val idx = indexOfCharOracle(s, delim)
    return if (idx < 0) s else s.substring(0, idx)
}

// ---------- block 97 shared ----------
// (No shared generator: every cluster's generator is used by exactly
// one test file, so each lives in its own GEN fragment below. The
// three device-state clusters use distinct generator names because
// all three fragments also land in the single Oracle97.kt.)

// ---------- ParseContactCard oracle ----------

// Hand-written reimplementation of
// AttachmentIntentPolicy.parseContactCard: strip the scheme ("://"
// first, else ":"), the query after "?" supplies name= (first
// match wins, blank falls back to caption, then "contact"),
// userId is the part before "?" trimmed. Built from the oracle
// helpers only, never the production call.
private fun parseContactCardOracle(uri: String, caption: String?): ContactCard {
    val afterScheme = substringAfterOracle(uri, "://", "")
    val withoutScheme = if (isEmptyOracle(afterScheme)) substringAfterOracle(uri, ":", "") else afterScheme
    val query = if (containsStrOracle(withoutScheme, "?")) substringAfterOracle(withoutScheme, "?", "") else ""
    val namePart = splitStringOracle(query, "&").firstOrNull { startsWithOracle(it, "name=") }
    val nameValue = namePart?.let { trimOracle(substringAfterOracle(it, "name=", "")) }
    val displayName = if (nameValue != null && !isBlankOracle(nameValue)) {
        nameValue
    } else {
        val cap = caption ?: ""
        if (isBlankOracle(cap)) "contact" else cap
    }
    return ContactCard(
        userId = trimOracle(substringBeforeStrOracle(withoutScheme, "?")),
        displayName = displayName,
    )
}

// ---------- contact-card oracle helpers ----------

private fun indexOfStrOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    if (needle.length > s.length) return -1
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return i
    }
    return -1
}

// String.substringAfter(delimiter, missingDelimiterValue): the part
// after the FIRST occurrence, or the missing value when absent.
private fun substringAfterOracle(s: String, delim: String, missing: String): String {
    val idx = indexOfStrOracle(s, delim)
    return if (idx < 0) missing else s.substring(idx + delim.length)
}

// String.substringBefore(delimiter): up to the first occurrence,
// or the whole string when absent.
private fun substringBeforeStrOracle(s: String, delim: String): String {
    val idx = indexOfStrOracle(s, delim)
    return if (idx < 0) s else s.substring(0, idx)
}

private fun containsStrOracle(s: String, needle: String): Boolean =
    indexOfStrOracle(s, needle) >= 0

// String.split(delimiter): every part, trailing empties kept.
private fun splitStringOracle(s: String, delim: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    while (true) {
        val idx = indexOfStrOracle(s.substring(start), delim)
        if (idx < 0) {
            out.add(s.substring(start))
            return out
        }
        out.add(s.substring(start, start + idx))
        start += idx + delim.length
    }
}

private fun isEmptyOracle(s: String): Boolean = s.length == 0

private val CONTACT_URI_POOL = arrayOf(
    "contact://u1?name=Ann",
    "contact://u1",
    "contact://u2?name=",
    "contact://u3?name=  ",
    "contact://?name=Ann",
    "contact://u4?name=A&name=B",
    "contact://u 5 ?name=X",
    "",
    "contact://",
    "://u6?name=C",
    "contact://u7?x=1&name=D",
    "contact://u8?name=E%20F",
    "tel:123",
    "contact://u9?name",
    "contact://u10?name= ",
    "contact:u11?name=Cid",
    "u12?name=Bob",
    "contact://u13?name=Ann?x=1",
    "contact://u14?NAME=Z",
    "contact://u15?name =Y",
)

private val CONTACT_CAPTION_POOL: Array<String?> = arrayOf(
    null, "", "  ", "Cap", "Bob", "  Ann  ",
)

private fun genContactUri(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1 -> CONTACT_URI_POOL[rnd.nextInt(CONTACT_URI_POOL.size)]
        2 -> "contact://" + randomToken(rnd) + "?name=" + randomToken(rnd)
        3 -> "contact://" + randomString(rnd, 20)
        4 -> randomString(rnd, 12)
        else -> "contact://" + randomToken(rnd) + "?x=1&name=" + randomString(rnd, 8)
    }

private fun genContactCaption(rnd: Random): String? =
    when (rnd.nextInt(6)) {
        0, 1 -> CONTACT_CAPTION_POOL[rnd.nextInt(CONTACT_CAPTION_POOL.size)]
        2 -> randomToken(rnd)
        else -> randomString(rnd, 8)
    }

    @Test
    fun contactRandom() {
        val rnd = Random(42)
        repeat(300) {
            val uri = genContactUri(rnd)
            val caption = genContactCaption(rnd)
            assertEquals(
                parseContactCardOracle(uri, caption),
                AttachmentIntentPolicy.parseContactCard(uri, caption)
            )
        }
    }

    @Test
    fun contactEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The edge leg cycles the URI pool against the caption
            // pool: a bare scheme or a blank name must fall back to
            // the caption, then to "contact".
            val uri = CONTACT_URI_POOL[rnd.nextInt(CONTACT_URI_POOL.size)]
            val caption = CONTACT_CAPTION_POOL[rnd.nextInt(CONTACT_CAPTION_POOL.size)]
            assertEquals(
                parseContactCardOracle(uri, caption),
                AttachmentIntentPolicy.parseContactCard(uri, caption)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // The name= query value wins over the caption.
        assertEquals(
            ContactCard("user-1", "Alice"),
            AttachmentIntentPolicy.parseContactCard("contact://user-1?name=Alice&x=1", null)
        )
        assertEquals(
            ContactCard("user-3", "Cid"),
            AttachmentIntentPolicy.parseContactCard("contact:user-3?name=Cid", null)
        )
        // No name= value: the caption is the display name.
        assertEquals(
            ContactCard("user-2", "Bob"),
            AttachmentIntentPolicy.parseContactCard("contact://user-2", "Bob")
        )
        // No name= value and no caption: "contact".
        assertEquals(
            ContactCard("user-2", "contact"),
            AttachmentIntentPolicy.parseContactCard("contact://user-2", null)
        )
        // A blank name= value falls back to the caption, then to
        // "contact" when the caption is blank too.
        assertEquals(
            ContactCard("u2", "Bob"),
            AttachmentIntentPolicy.parseContactCard("contact://u2?name=", "Bob")
        )
        assertEquals(
            ContactCard("u3", "contact"),
            AttachmentIntentPolicy.parseContactCard("contact://u3?name=  ", "  ")
        )
        // The FIRST name= wins.
        assertEquals(
            ContactCard("u4", "A"),
            AttachmentIntentPolicy.parseContactCard("contact://u4?name=A&name=B", null)
        )
        // A name= after another query key still counts.
        assertEquals(
            ContactCard("u7", "D"),
            AttachmentIntentPolicy.parseContactCard("contact://u7?x=1&name=D", null)
        )
        // "NAME=" does not count: the match is case-sensitive.
        assertEquals(
            ContactCard("u14", "contact"),
            AttachmentIntentPolicy.parseContactCard("contact://u14?NAME=Z", null)
        )
        // "name =" (space before =) does not count either.
        assertEquals(
            ContactCard("u15", "contact"),
            AttachmentIntentPolicy.parseContactCard("contact://u15?name =Y", null)
        )
        // "name" without "=" does not count.
        assertEquals(
            ContactCard("u9", "contact"),
            AttachmentIntentPolicy.parseContactCard("contact://u9?name", null)
        )
        // The userId is the part before "?", trimmed.
        assertEquals(
            ContactCard("u 5", "X"),
            AttachmentIntentPolicy.parseContactCard("contact://u 5 ?name=X", null)
        )
        assertEquals(
            ContactCard("", "Ann"),
            AttachmentIntentPolicy.parseContactCard("contact://?name=Ann", null)
        )
        // "://" wins over a later ":"; a bare ":" scheme still
        // strips the scheme.
        assertEquals(
            ContactCard("123", "contact"),
            AttachmentIntentPolicy.parseContactCard("tel:123", null)
        )
        // No scheme and no ":" at all: the userId is empty and the
        // query is never seen, so the caption is the display name.
        assertEquals(
            ContactCard("", "Bob"),
            AttachmentIntentPolicy.parseContactCard("u12?name=Bob", "Bob")
        )
        assertEquals(
            ContactCard("", "contact"),
            AttachmentIntentPolicy.parseContactCard("u12?name=Bob", null)
        )
        // A second "?" stays inside the name value.
        assertEquals(
            ContactCard("u13", "Ann?x=1"),
            AttachmentIntentPolicy.parseContactCard("contact://u13?name=Ann?x=1", null)
        )
    }
}
