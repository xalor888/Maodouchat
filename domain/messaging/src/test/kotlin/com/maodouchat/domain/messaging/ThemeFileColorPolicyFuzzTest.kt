// Fuzz / random-oracle tests for ThemeFileColorPolicy (checklist line 931,
// 78th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. parseColorValue takes #RRGGBB / #AARRGGBB via toLong(16) else a decimal toInt(); parseHexArgb trims, strips one '#', keeps only hex chars; parseAtTheme only maps TG keys; parseThemeFile layers direct slot keys over at-theme without overwriting; formatArgb is #%08X unsigned; exportKeyForSlot is the first TG key per slot.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeFileColorPolicyFuzzTest {

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


// ---------- ThemeFileColorPolicy oracle ----------

private val THEME_SLOTS_ORACLE = listOf(
    "accent",
    "chat_background",
    "chat_inBubble",
    "chat_inText",
    "chat_outBubble",
    "chat_outText",
    "text_primary",
    "window_background",
    "input_background",
    "unread_badge",
    "system_message"
)

private val TG_KEY_MAP_ORACLE = linkedMapOf(
    "actionBarDefaultAction" to "accent",
    "actionBarDefaultIcon" to "accent",
    "chat_inBubble" to "chat_inBubble",
    "chat_inTextColor" to "chat_inText",
    "chat_messagePanelBackground" to "chat_background",
    "chat_messagePanelBackground2" to "input_background",
    "chat_outBubble" to "chat_outBubble",
    "chat_outTextColor" to "chat_outText",
    "chat_unreadCounter" to "unread_badge",
    "chat_serviceMessageBackground" to "system_message",
    "chat_wallpaper" to "chat_background",
    "windowBackgroundWhite" to "window_background",
    "windowBackgroundWhiteBlackText" to "text_primary"
)

// First TG key per slot in map insertion order (groupBy + first()).
private val EXPORT_KEY_MAP_ORACLE: Map<String, String> = run {
    val out = linkedMapOf<String, String>()
    for ((k, v) in TG_KEY_MAP_ORACLE) {
        if (v !in out) out[v] = k
    }
    out
}

private fun exportKeyForSlotOracle(slot: String): String? = EXPORT_KEY_MAP_ORACLE[slot]

private fun parseColorValueOracle(value: String): Int? {
    if (startsWithOracle(value, "#")) {
        val hex = dropOracle(value, 1)
        return when (hex.length) {
            6 -> parseLongRadixOracle("FF" + hex, 16)?.toInt()
            8 -> parseLongRadixOracle(hex, 16)?.toInt()
            else -> null
        }
    }
    return parseIntOracle(value)
}

private fun parseHexArgbOracle(raw: String): Int? {
    val stripped = removePrefixOracle(trimOracle(raw), "#")
    val sb = StringBuilder(stripped.length)
    for (c in stripped) {
        if (isDigitOracle(c) || c in 'a'..'f' || c in 'A'..'F') sb.append(c)
    }
    val hex = sb.toString()
    return when (hex.length) {
        6 -> parseLongRadixOracle("FF" + hex, 16)?.toInt()
        8 -> parseLongRadixOracle(hex, 16)?.toInt()
        else -> null
    }
}

private fun parseAtThemeOracle(text: String): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (line in splitLinesOracle(text)) {
        val trimmed = trimOracle(line)
        if (trimmed.isEmpty() || startsWithOracle(trimmed, "//")) continue
        val eq = indexOfCharOracle(trimmed, '=')
        if (eq <= 0) continue
        val tgKey = trimOracle(trimmed.substring(0, eq))
        val slot = TG_KEY_MAP_ORACLE[tgKey] ?: continue
        val value = trimOracle(trimmed.substring(eq + 1))
        val color = parseColorValueOracle(value) ?: continue
        out[slot] = color
    }
    return out
}

private fun parseThemeFileOracle(text: String): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    for ((k, v) in parseAtThemeOracle(text)) out[k] = v
    val known = THEME_SLOTS_ORACLE.toSet()
    for (line in splitLinesOracle(text)) {
        val trimmed = trimOracle(line)
        if (trimmed.isEmpty() || startsWithOracle(trimmed, "//")) continue
        val eq = indexOfCharOracle(trimmed, '=')
        if (eq <= 0) continue
        val key = trimOracle(trimmed.substring(0, eq))
        if (key !in known || out.containsKey(key)) continue
        val value = trimOracle(trimmed.substring(eq + 1))
        val color = parseHexArgbOracle(value) ?: parseIntOracle(value)
        if (color != null) out[key] = color
    }
    return out
}

private fun formatArgbOracle(argb: Int): String {
    val hex = "0123456789ABCDEF"
    val u = argb.toLong() and 0xFFFFFFFFL
    val sb = StringBuilder("#")
    for (shift in listOf(28, 24, 20, 16, 12, 8, 4, 0)) {
        sb.append(hex[((u shr shift) and 0xFL).toInt()])
    }
    return sb.toString()
}

private val THEME_HEX_POOL = listOf(
    "", "#", "#FF0000", "#ff0000", "#00FF00", "#0000FF", "#123456",
    "#12345678", "#FFFFFFFF", "#00000000", "#abcdef", "#ABCDEF",
    "#12345", "#1234567", "#123456789", "#GGGGGG", "#12 34 56",
    "#-FF0000", "#+FF0000", "123", "-123", "+123", "0", "2147483647",
    "-2147483648", "2147483648", "4294967295", "  123", "123  ", "12a3",
    "#FFFFFF", "#ffffff", "#FfFfFf", "#1a2B3c", "#1A2B3C4D", "12",
    "#12", "#1234567G", "١٢٣", "#١٢٣٤٥٦", "٣", "#a", "#FF", "#FFFF"
)

private val THEME_SLOT_POOL = listOf(
    "accent", "chat_background", "chat_inBubble", "chat_inText",
    "chat_outBubble", "chat_outText", "text_primary",
    "window_background", "input_background", "unread_badge",
    "system_message", "unknown_slot", "", "Accent", "accent "
)

private val THEME_TG_POOL = listOf(
    "actionBarDefaultAction", "actionBarDefaultIcon", "chat_inBubble",
    "chat_inTextColor", "chat_messagePanelBackground",
    "chat_messagePanelBackground2", "chat_outBubble", "chat_outTextColor",
    "chat_unreadCounter", "chat_serviceMessageBackground",
    "chat_wallpaper", "windowBackgroundWhite",
    "windowBackgroundWhiteBlackText", "unknownKey", "", "accent",
    "chat_inBubble ", " chat_inBubble"
)

private fun genThemeValue(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> THEME_HEX_POOL[rnd.nextInt(THEME_HEX_POOL.size)]
        6 -> randomToken(rnd)
        7 -> randomWord(rnd)
        else -> randomString(rnd, 16)
    }

private fun genThemeLine(rnd: Random): String {
    val key = when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> THEME_TG_POOL[rnd.nextInt(THEME_TG_POOL.size)]
        6, 7 -> THEME_SLOT_POOL[rnd.nextInt(THEME_SLOT_POOL.size)]
        8 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }
    val value = genThemeValue(rnd)
    val shape = rnd.nextInt(10)
    return when (shape) {
        0 -> "$key=$value"
        1 -> "$key = $value"
        2 -> "  $key=$value  "
        3 -> "// comment"
        4 -> ""
        5 -> key
        6 -> "$key="
        7 -> "=$value"
        8 -> "$key=$value=extra"
        else -> "$key=$value"
    }
}

private fun genThemeText(rnd: Random): String {
    val n = rnd.nextInt(0, 7)
    val sep = when (rnd.nextInt(4)) {
        0 -> "\n"
        1 -> "\r\n"
        2 -> "\r"
        else -> "\n"
    }
    val lines = List(n) { genThemeLine(rnd) }
    val joined = lines.joinToString(sep)
    return if (rnd.nextInt(4) == 0) joined + sep else joined
}

private fun genThemeInt(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0 -> 0
        1 -> -1
        2 -> Int.MAX_VALUE
        3 -> Int.MIN_VALUE
        4 -> 0xFF000000.toInt()
        5 -> 0x00FFFFFF
        6 -> rnd.nextInt()
        7 -> rnd.nextInt(-0x1000000, 0x1000000)
        else -> rnd.nextInt()
    }

    @Test
    fun themeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val value = genThemeValue(rnd)
            val raw = genThemeValue(rnd)
            val text = genThemeText(rnd)
            val argb = genThemeInt(rnd)
            val slot = when (rnd.nextInt(10)) {
                0, 1, 2, 3, 4, 5, 6, 7 -> THEME_SLOT_POOL[rnd.nextInt(THEME_SLOT_POOL.size)]
                8 -> randomToken(rnd)
                else -> randomString(rnd, 12)
            }
            assertEquals(parseColorValueOracle(value), ThemeFileColorPolicy.parseColorValue(value))
            assertEquals(parseHexArgbOracle(raw), ThemeFileColorPolicy.parseHexArgb(raw))
            assertEquals(parseAtThemeOracle(text), ThemeFileColorPolicy.parseAtTheme(text))
            assertEquals(parseThemeFileOracle(text), ThemeFileColorPolicy.parseThemeFile(text))
            assertEquals(formatArgbOracle(argb), ThemeFileColorPolicy.formatArgb(argb))
            assertEquals(exportKeyForSlotOracle(slot), ThemeFileColorPolicy.exportKeyForSlot(slot))
        }
    }

    @Test
    fun themeTextRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Text-heavy: line shapes and key/value placement decide
            // which slots survive both parsers.
            val text = genThemeText(rnd)
            assertEquals(parseAtThemeOracle(text), ThemeFileColorPolicy.parseAtTheme(text))
            assertEquals(parseThemeFileOracle(text), ThemeFileColorPolicy.parseThemeFile(text))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // parseColorValue: 6-hex gets FF alpha, 8-hex is raw ARGB.
        assertEquals(0xFFFF0000.toInt(), ThemeFileColorPolicy.parseColorValue("#FF0000"))
        assertEquals(0xFFFF0000.toInt(), ThemeFileColorPolicy.parseColorValue("#ff0000"))
        assertEquals(0x80123456.toInt(), ThemeFileColorPolicy.parseColorValue("#80123456"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue("#12345"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue("#1234567"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue("#GGGGGG"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue("#-FF0000"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue(""))
        // Decimal branch: plain toInt(), no trim, signs allowed.
        assertEquals(123, ThemeFileColorPolicy.parseColorValue("123"))
        assertEquals(-123, ThemeFileColorPolicy.parseColorValue("-123"))
        assertEquals(123, ThemeFileColorPolicy.parseColorValue("+123"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue("  123"))
        assertEquals(null, ThemeFileColorPolicy.parseColorValue("2147483648"))
        assertEquals(-2147483648, ThemeFileColorPolicy.parseColorValue("-2147483648"))
        assertEquals(123, ThemeFileColorPolicy.parseColorValue("١٢٣"))
        // parseHexArgb: trims, strips one '#', keeps only hex chars.
        assertEquals(0xFFFF0000.toInt(), ThemeFileColorPolicy.parseHexArgb("  #FF0000  "))
        assertEquals(0xFF123456.toInt(), ThemeFileColorPolicy.parseHexArgb("#12 34 56"))
        assertEquals(0xFFFF0000.toInt(), ThemeFileColorPolicy.parseHexArgb("##FF0000"))
        assertEquals(null, ThemeFileColorPolicy.parseHexArgb("#GGGGGG"))
        assertEquals(0xFF123456.toInt(), ThemeFileColorPolicy.parseHexArgb("123456"))
        assertEquals(0x80123456.toInt(), ThemeFileColorPolicy.parseHexArgb("80123456"))
        assertEquals(null, ThemeFileColorPolicy.parseHexArgb("12345"))
        // parseAtTheme: only TG keys map; unknown keys and bad values drop.
        assertEquals(
            mapOf("accent" to 0xFFFF0000.toInt()),
            ThemeFileColorPolicy.parseAtTheme("actionBarDefaultAction=#FF0000")
        )
        assertEquals(
            mapOf("accent" to 0xFFFF0000.toInt(), "chat_inText" to 0xFF00FF00.toInt()),
            ThemeFileColorPolicy.parseAtTheme(
                "actionBarDefaultAction=#FF0000\nunknownKey=#00FF00\nchat_inTextColor=#00FF00"
            )
        )
        assertEquals(
            emptyMap<String, Int>(),
            ThemeFileColorPolicy.parseAtTheme("// comment\n\nnoequals\n=123\naccent=zzz")
        )
        // parseThemeFile: direct slot keys layer over at-theme, but
        // never overwrite an at-theme slot.
        assertEquals(
            mapOf("accent" to 0xFFFF0000.toInt(), "text_primary" to 0xFF00FF00.toInt()),
            ThemeFileColorPolicy.parseThemeFile(
                "actionBarDefaultAction=#FF0000\ntext_primary=#00FF00"
            )
        )
        assertEquals(
            mapOf("accent" to 0xFFFF0000.toInt()),
            ThemeFileColorPolicy.parseThemeFile(
                "actionBarDefaultAction=#FF0000\naccent=#00FF00"
            )
        )
        assertEquals(
            mapOf("accent" to 1),
            ThemeFileColorPolicy.parseThemeFile("accent=1")
        )
        // formatArgb: unsigned %08X.
        assertEquals("#FFFFFFFF", ThemeFileColorPolicy.formatArgb(-1))
        assertEquals("#00000000", ThemeFileColorPolicy.formatArgb(0))
        assertEquals("#80000000", ThemeFileColorPolicy.formatArgb(Int.MIN_VALUE))
        assertEquals("#7FFFFFFF", ThemeFileColorPolicy.formatArgb(Int.MAX_VALUE))
        assertEquals("#FF123456", ThemeFileColorPolicy.formatArgb(0xFF123456.toInt()))
        // exportKeyForSlot: first TG key per slot in insertion order.
        assertEquals("actionBarDefaultAction", ThemeFileColorPolicy.exportKeyForSlot("accent"))
        assertEquals("chat_messagePanelBackground", ThemeFileColorPolicy.exportKeyForSlot("chat_background"))
        assertEquals("chat_messagePanelBackground2", ThemeFileColorPolicy.exportKeyForSlot("input_background"))
        assertEquals("chat_inBubble", ThemeFileColorPolicy.exportKeyForSlot("chat_inBubble"))
        assertEquals(null, ThemeFileColorPolicy.exportKeyForSlot("unknown"))
        assertEquals(
            listOf(
                "accent", "chat_background", "chat_inBubble", "chat_inText",
                "chat_outBubble", "chat_outText", "text_primary",
                "window_background", "input_background", "unread_badge",
                "system_message"
            ),
            ThemeFileColorPolicy.SLOTS
        )
    }
}
