// Fuzz / random-oracle tests for MediaViewer (checklist line 931,
// 100th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. MediaViewerPolicy: image/video mime checks, double-tap scale toggle, scale clamp (NaN falls back), export/share gates. defaultMime/defaultFileName are NOT covered here (see emit100.py).
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaViewerFuzzTest {

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

// ---------- block-100 common oracle helpers ----------

private fun endsWithOracle(s: String, suffix: String): Boolean {
    if (suffix.length > s.length) return false
    for (i in suffix.indices) {
        if (s[s.length - suffix.length + i] != suffix[i]) return false
    }
    return true
}

// Kotlin split(Char): split on every occurrence, empties kept.
private fun splitCharOracle(s: String, delim: Char): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    for (i in s.indices) {
        if (s[i] == delim) {
            out.add(s.substring(start, i))
            start = i + 1
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun substringAfterCharOracle(s: String, delim: Char, missing: String): String {
    for (i in s.indices) {
        if (s[i] == delim) return s.substring(i + 1)
    }
    return missing
}

private fun equalsIgnoreCaseOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (!charsEqualIgnoreCaseOracle(a[i], b[i])) return false
    }
    return true
}

private fun indexOfIgnoreCaseOracle(s: String, needle: String, from: Int): Int {
    if (needle.isEmpty()) return from.coerceIn(0, s.length)
    if (needle.length > s.length) return -1
    var i = from.coerceAtLeast(0)
    while (i + needle.length <= s.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return i
        i++
    }
    return -1
}

private fun indexOfCharFromOracle(s: String, ch: Char, from: Int): Int {
    var i = from.coerceAtLeast(0)
    while (i < s.length) {
        if (s[i] == ch) return i
        i++
    }
    return -1
}

// Java String.lastIndexOf(ch, fromIndex): backward from fromIndex
// (clamped to the last unit); -1 when fromIndex < 0.
private fun lastIndexOfCharOracle(s: String, ch: Char, from: Int): Int {
    if (from < 0) return -1
    var i = minOf(from, s.length - 1)
    while (i >= 0) {
        if (s[i] == ch) return i
        i--
    }
    return -1
}

private fun isAsciiWsUnit(code: Int): Boolean =
    code == 0x09 || code == 0x0A || code == 0x0B || code == 0x0C ||
        code == 0x0D || code == 0x20

// Kotlin Regex("\\s+") split: Java Pattern \s is ASCII whitespace
// only (no UNICODE_CHARACTER_CLASS). Empties are dropped by the
// caller's trim/filter, mirroring the production pipeline.
private fun splitAsciiWsOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        if (isAsciiWsUnit(s[i].code)) {
            if (i > start) out.add(s.substring(start, i))
            while (i < s.length && isAsciiWsUnit(s[i].code)) i++
            start = i
        } else {
            i++
        }
    }
    if (start < s.length) out.add(s.substring(start, s.length))
    return out
}

private fun collapseAsciiWsOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (isAsciiWsUnit(s[i].code)) {
            sb.append(' ')
            while (i < s.length && isAsciiWsUnit(s[i].code)) i++
        } else {
            sb.append(s[i])
            i++
        }
    }
    return sb.toString()
}

// ---------- MediaViewer oracle ----------

// Hand-written reimplementation of MediaViewerPolicy (minus
// defaultMime, which collides by bare name with block 75's
// AgentAudioRequestPolicy.defaultMime, and defaultFileName, which
// stamps System.currentTimeMillis). Float logic is hand-written on
// Float values; NaN is the only special case (coerceIn passes NaN
// through, production guards it first).
private fun isImageMimeOracle(mime: String?): Boolean {
    val m = lowercaseOracle(mime ?: "")
    return startsWithOracle(m, "image/") || equalsOracle(m, "image") ||
        endsWithOracle(m, "gif")
}

private fun isVideoMimeOracle(mime: String?): Boolean {
    val m = lowercaseOracle(mime ?: "")
    return startsWithOracle(m, "video/")
}

private fun nextDoubleTapScaleOracle(current: Float): Float =
    if (current > 1.05f) 1f else 3.5f

private fun clampScaleOracle(scale: Float): Float {
    if (scale.isNaN()) return 1f
    if (scale < 1f) return 1f
    if (scale > 8f) return 8f
    return scale
}

private fun canExportLocalOracle(
    localReadable: Boolean,
    secretChat: Boolean,
    exportBlockEnabled: Boolean,
): Boolean = localReadable && !secretChat

private fun canShareLocalOracle(
    localReadable: Boolean,
    secretChat: Boolean,
    exportBlockEnabled: Boolean,
): Boolean = localReadable && !secretChat

private val MEDIA_MIME_POOL100 = arrayOf<String?>(
    null, "", " ", "image/png", "IMAGE/PNG", "image/gif", "image",
    "image/", "video/mp4", "VIDEO/MP4", "video", "application/pdf",
    "text/gif", "agif", "gif", "xgif", "image/gif ", " image/png",
    "video/mp4; codecs", "image/jpeg", "IMAGE/GIF", "video/",
)

private val MEDIA_SCALE_BITS_POOL100 = intArrayOf(
    0x00000000, // 0.0f
    -2147483648, // -0.0f (0x80000000)
    0x3F800000, // 1.0f
    0x3F866666, // 1.05f
    0x3F866667, // next float above 1.05f
    0x3F866665, // next float below 1.05f
    0x40600000, // 3.5f
    0x41000000, // 8.0f
    0x41080000, // 8.5f
    -1082130432, // -1.0f (0xBF800000)
    0x7FC00000, // NaN
    0x7F800000, // +Inf
    -8388608, // -Inf (0xFF800000)
    0x00000001, // smallest subnormal
    0x7F7FFFFF, // largest finite
    0x3E800000, // 0.25f
    0x40000000, // 2.0f
    0x40E00000, // 7.0f
)

private fun genMediaMime(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> MEDIA_MIME_POOL100[rnd.nextInt(MEDIA_MIME_POOL100.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 8)
    }

private fun genMediaMimeEdge(rnd: Random): String? =
    MEDIA_MIME_POOL100[rnd.nextInt(MEDIA_MIME_POOL100.size)]

private fun genMediaScale(rnd: Random): Float =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> Float.fromBits(
            MEDIA_SCALE_BITS_POOL100[rnd.nextInt(MEDIA_SCALE_BITS_POOL100.size)]
        )
        6 -> rnd.nextFloat() * 10f - 1f
        else -> Float.fromBits(rnd.nextInt())
    }

private fun genMediaScaleEdge(rnd: Random): Float =
    Float.fromBits(
        MEDIA_SCALE_BITS_POOL100[rnd.nextInt(MEDIA_SCALE_BITS_POOL100.size)]
    )

    @Test
    fun mediaViewerRandom() {
        val rnd = Random(42)
        repeat(300) {
            val mime = genMediaMime(rnd)
            val scale = genMediaScale(rnd)
            val readable = rnd.nextBoolean()
            val secret = rnd.nextBoolean()
            val block = rnd.nextBoolean()
            assertEquals(isImageMimeOracle(mime), MediaViewerPolicy.isImageMime(mime))
            assertEquals(isVideoMimeOracle(mime), MediaViewerPolicy.isVideoMime(mime))
            assertEquals(nextDoubleTapScaleOracle(scale), MediaViewerPolicy.nextDoubleTapScale(scale))
            assertEquals(clampScaleOracle(scale), MediaViewerPolicy.clampScale(scale))
            assertEquals(
                canExportLocalOracle(readable, secret, block),
                MediaViewerPolicy.canExportLocal(readable, secret, block)
            )
            assertEquals(
                canShareLocalOracle(readable, secret, block),
                MediaViewerPolicy.canShareLocal(readable, secret, block)
            )
        }
    }

    @Test
    fun mediaViewerEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The edge leg cycles the pools: bare "image", gif
            // suffixes, padded mimes, the 1.05 toggle boundary,
            // NaN / infinities / subnormals, and every boolean
            // combination of readable/secret/blocked.
            val mime = genMediaMimeEdge(rnd)
            val scale = genMediaScaleEdge(rnd)
            val readable = rnd.nextBoolean()
            val secret = rnd.nextBoolean()
            val block = rnd.nextBoolean()
            assertEquals(isImageMimeOracle(mime), MediaViewerPolicy.isImageMime(mime))
            assertEquals(isVideoMimeOracle(mime), MediaViewerPolicy.isVideoMime(mime))
            assertEquals(nextDoubleTapScaleOracle(scale), MediaViewerPolicy.nextDoubleTapScale(scale))
            assertEquals(clampScaleOracle(scale), MediaViewerPolicy.clampScale(scale))
            assertEquals(
                canExportLocalOracle(readable, secret, block),
                MediaViewerPolicy.canExportLocal(readable, secret, block)
            )
            assertEquals(
                canShareLocalOracle(readable, secret, block),
                MediaViewerPolicy.canShareLocal(readable, secret, block)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Image mime: image/*, bare "image", or a gif suffix.
        assertEquals(true, MediaViewerPolicy.isImageMime("image/png"))
        assertEquals(true, MediaViewerPolicy.isImageMime("IMAGE/PNG"))
        assertEquals(true, MediaViewerPolicy.isImageMime("image"))
        assertEquals(true, MediaViewerPolicy.isImageMime("text/gif"))
        assertEquals(true, MediaViewerPolicy.isImageMime("agif"))
        assertEquals(false, MediaViewerPolicy.isImageMime("video/mp4"))
        assertEquals(false, MediaViewerPolicy.isImageMime("application/pdf"))
        assertEquals(false, MediaViewerPolicy.isImageMime(null))
        assertEquals(false, MediaViewerPolicy.isImageMime(""))
        // Video mime: only video/*.
        assertEquals(true, MediaViewerPolicy.isVideoMime("video/mp4"))
        assertEquals(true, MediaViewerPolicy.isVideoMime("VIDEO/MP4"))
        assertEquals(false, MediaViewerPolicy.isVideoMime("video"))
        assertEquals(false, MediaViewerPolicy.isVideoMime("image/png"))
        assertEquals(false, MediaViewerPolicy.isVideoMime(null))
        // Double-tap toggles at 1.05; NaN counts as "not above".
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(1.0f))
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(1.05f))
        assertEquals(1.0f, MediaViewerPolicy.nextDoubleTapScale(1.06f))
        assertEquals(1.0f, MediaViewerPolicy.nextDoubleTapScale(3.5f))
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(Float.NaN))
        // Clamp: NaN falls back to the minimum, infinities clamp.
        assertEquals(1.0f, MediaViewerPolicy.clampScale(Float.NaN))
        assertEquals(1.0f, MediaViewerPolicy.clampScale(0.5f))
        assertEquals(1.0f, MediaViewerPolicy.clampScale(-3.0f))
        assertEquals(8.0f, MediaViewerPolicy.clampScale(9.0f))
        assertEquals(8.0f, MediaViewerPolicy.clampScale(Float.POSITIVE_INFINITY))
        assertEquals(1.0f, MediaViewerPolicy.clampScale(Float.NEGATIVE_INFINITY))
        assertEquals(3.5f, MediaViewerPolicy.clampScale(3.5f))
        // Export/share: readable and not a secret chat; the block
        // flag is unused by the policy.
        assertEquals(true, MediaViewerPolicy.canExportLocal(true, false, true))
        assertEquals(true, MediaViewerPolicy.canExportLocal(true, false, false))
        assertEquals(false, MediaViewerPolicy.canExportLocal(false, false, true))
        assertEquals(false, MediaViewerPolicy.canExportLocal(true, true, true))
        assertEquals(true, MediaViewerPolicy.canShareLocal(true, false, true))
        assertEquals(false, MediaViewerPolicy.canShareLocal(true, true, false))
    }
}
