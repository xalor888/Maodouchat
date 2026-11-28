package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for ThemeModePolicy (checklist line 931,
// 66th block). Test-only, zero production changes. The oracle is
// hand-written: trim strips leading/trailing units in the probed 28-unit
// Kotlin whitespace set, lowercase is per-code-point, and the whitelist
// is checked unit by unit. Null and anything outside
// {light, dark, scheduled} falls back to "system".
class ThemeModePolicyFuzzTest {

// ---------- shared oracle (all four clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL does not. Hand-written, never calls
// Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Per-code-point simple case mapping. Agrees with String lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
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

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// ---------- ThemeModePolicy oracle ----------
// null -> "system"; trim + lowercase, then light/dark/scheduled,
// anything else -> "system".
private fun modeOracle(raw: String?): String {
    if (raw == null) return "system"
    val t = lowercaseOracle(trimOracle(raw))
    if (equalsOracle(t, "light")) return "light"
    if (equalsOracle(t, "dark")) return "dark"
    if (equalsOracle(t, "scheduled")) return "scheduled"
    return "system"
}

// ---------- ThemeStylePolicy oracle ----------
// null -> "maodou"; tg/tg_classic/telegram/telegram_classic all map to
// tg_classic, tg_midnight/midnight to tg_midnight,
// tg_graphite/graphite to tg_graphite, anything else -> "maodou".
private fun styleOracle(raw: String?): String {
    if (raw == null) return "maodou"
    val t = lowercaseOracle(trimOracle(raw))
    if (equalsOracle(t, "tg_classic")) return "tg_classic"
    if (equalsOracle(t, "tg")) return "tg_classic"
    if (equalsOracle(t, "telegram")) return "tg_classic"
    if (equalsOracle(t, "telegram_classic")) return "tg_classic"
    if (equalsOracle(t, "tg_midnight")) return "tg_midnight"
    if (equalsOracle(t, "midnight")) return "tg_midnight"
    if (equalsOracle(t, "tg_graphite")) return "tg_graphite"
    if (equalsOracle(t, "graphite")) return "tg_graphite"
    return "maodou"
}

// ---------- ThemeAccentPolicy oracle ----------
// null -> "none"; the eight ids none/blue/green/purple/orange/pink/red/
// teal pass through, anything else -> "none".
private fun accentOracle(raw: String?): String {
    if (raw == null) return "none"
    val t = lowercaseOracle(trimOracle(raw))
    if (equalsOracle(t, "none")) return "none"
    if (equalsOracle(t, "blue")) return "blue"
    if (equalsOracle(t, "green")) return "green"
    if (equalsOracle(t, "purple")) return "purple"
    if (equalsOracle(t, "orange")) return "orange"
    if (equalsOracle(t, "pink")) return "pink"
    if (equalsOracle(t, "red")) return "red"
    if (equalsOracle(t, "teal")) return "teal"
    return "none"
}

// ---------- ChatBubbleColorPolicy oracle ----------
// null -> "green" (GREEN); the six ids blue/green/purple/orange/pink/
// teal pass through, anything else -- including "red" and "none", which
// are legal for ThemeAccentPolicy -- -> "green".
private fun bubbleOracle(raw: String?): String {
    if (raw == null) return "green"
    val t = lowercaseOracle(trimOracle(raw))
    if (equalsOracle(t, "blue")) return "blue"
    if (equalsOracle(t, "green")) return "green"
    if (equalsOracle(t, "purple")) return "purple"
    if (equalsOracle(t, "orange")) return "orange"
    if (equalsOracle(t, "pink")) return "pink"
    if (equalsOracle(t, "teal")) return "teal"
    return "green"
}

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase/toUpperCase, what the oracle uses) and
// the full string mapping (String.lowercase()/uppercase(), what
// production uses). Excluded on purpose: Greek (word-final sigma),
// Armenian, ligatures U+FB00-FB17, U+0130 (İ -> i + combining dot),
// U+00DF (ß -> SS when uppercased), U+0149, U+01F0, Cherokee,
// Georgian -- all special-casing code points.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0131 to 0x0148, 0x014A to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomCaseSafeString(rnd: Random, maxLen: Int = 40): String {
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
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// ---------- block-66 generators ----------

// Near-miss pools: every legal value / alias in lower, UPPER and
// Capitalized form, padded, truncated and near-miss spellings, so the
// trim + lowercase + whitelist pipeline is exercised on both sides of
// every edge. "LIGHT" / "TG_CLASSIC" carry an uppercase I: under the
// JDK default locale (en_US, probed) they lowercase to "light" /
// "tg_classic"; the oracle uses the locale-independent per-code-point
// mapping and the differential drive checks they agree on every input.
private val MODE_POOL = arrayOf(
    "", " ", "  ", "light", "LIGHT", "Light", "lIgHt", " light ",
    "dark", "DARK", "Dark", "dArK", " dark ", "scheduled", "SCHEDULED",
    "Scheduled", "sChEdUlEd", "system", "SYSTEM", "System", "auto",
    "amoled", "ligh", "lightx", "darkk", "schedule", "scheduledx",
    "l", "d", "s", "LIGHT\u00a0", "\u00a0light", "\u2007dark\u2007",
    "\u0085light\u0085", "\u3000scheduled\u3000", "light\n", "\tlight",
    " light\t", "LIGHT ", " DARK"
)

private val STYLE_POOL = arrayOf(
    "", " ", "maodou", "MAODOU", "Maodou", " maodou ", "tg_classic",
    "TG_CLASSIC", "Tg_Classic", "tg", "TG", "Tg", " tg ", "telegram",
    "TELEGRAM", "Telegram", "telegram_classic", "TELEGRAM_CLASSIC",
    "Telegram_Classic", "tg_midnight", "TG_MIDNIGHT", "midnight",
    "MIDNIGHT", "Midnight", " midnight ", "tg_graphite", "TG_GRAPHITE",
    "graphite", "GRAPHITE", "Graphite", " graphite ", "material",
    "MATERIAL", "tg_classi", "tg_midnigh", "midnigh", "graphit",
    "tg-", "tg_classicx", "tgclassic", "tg classic",
    "TG_MIDNIGHT\u00a0", "\u00a0tg\u00a0", "\u2007midnight\u2007",
    "\u0085graphite\u0085", "telegram ", " telegram"
)

private val ACCENT_POOL = arrayOf(
    "", " ", "none", "NONE", "None", " none ", "blue", "BLUE", "Blue",
    "green", "GREEN", "Green", "purple", "PURPLE", "orange", "ORANGE",
    "pink", "PINK", "red", "RED", "Red", "teal", "TEAL", "Teal",
    "gold", "GOLD", "yellow", "cyan", "blu", "bluee", "gree", "redd",
    "nonex", "n", "b", " blue", "red ", "TEAL\u00a0", "\u00a0pink\u00a0",
    "\u2007orange\u2007", "\u0085red\u0085", "rEd", "pInK"
)

private val BUBBLE_POOL = arrayOf(
    "", " ", "blue", "BLUE", "Blue", "green", "GREEN", "Green",
    "purple", "PURPLE", "orange", "ORANGE", "pink", "PINK", "teal",
    "TEAL", "red", "RED", "Red", "none", "NONE", "None", "gold",
    "yellow", "cyan", "blu", "bluee", "gree", "nonex", " blue",
    "green ", "TEAL\u00a0", "\u00a0pink\u00a0", "\u2007orange\u2007",
    "\u0085blue\u0085", "rEd", "gReEn", "tealx"
)

private fun randomThemeRaw(rnd: Random, kind: String): String? {
    val pool = when (kind) {
        "mode" -> MODE_POOL
        "style" -> STYLE_POOL
        "accent" -> ACCENT_POOL
        else -> BUBBLE_POOL
    }
    return when (rnd.nextInt(6)) {
        0 -> null
        1 -> pool[rnd.nextInt(pool.size)]
        2 -> randomCaseSafeString(rnd, 12).uppercase()
        3 -> randomCaseSafeString(rnd, 12)
        4 -> pool[rnd.nextInt(pool.size)]
        else -> randomCaseSafeString(rnd, 24)
    }
}

    @Test
    fun normalizeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = randomThemeRaw(rnd, "mode")
            assertEquals(modeOracle(raw), ThemeModePolicy.normalizeThemeMode(raw))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        assertEquals("system", ThemeModePolicy.normalizeThemeMode(null))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode(""))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode(" "))
        assertEquals("light", ThemeModePolicy.normalizeThemeMode("light"))
        assertEquals("light", ThemeModePolicy.normalizeThemeMode("LIGHT"))
        assertEquals("light", ThemeModePolicy.normalizeThemeMode(" Light "))
        assertEquals("dark", ThemeModePolicy.normalizeThemeMode("dark"))
        assertEquals("dark", ThemeModePolicy.normalizeThemeMode("DARK"))
        assertEquals("scheduled", ThemeModePolicy.normalizeThemeMode("scheduled"))
        assertEquals("scheduled", ThemeModePolicy.normalizeThemeMode("SCHEDULED"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("system"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("SYSTEM"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("auto"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("amoled"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("ligh"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("lightx"))
        assertEquals("light", ThemeModePolicy.normalizeThemeMode(" light "))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("light"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("LİGHT"))
        assertEquals("system", ThemeModePolicy.normalizeThemeMode("lİght"))
        assertEquals("dark", ThemeModePolicy.normalizeThemeMode(" dark "))
        assertEquals("light", ThemeModePolicy.normalizeThemeMode("light\n"))
        assertEquals("dark", ThemeModePolicy.normalizeThemeMode("\tDARK"))
        assertEquals("scheduled", ThemeModePolicy.normalizeThemeMode("sChEdUlEd"))
    }
}
