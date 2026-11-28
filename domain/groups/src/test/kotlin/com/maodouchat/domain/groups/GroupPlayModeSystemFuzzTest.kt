// GroupPlayModeSystem format/parse pairs (145th block). Oracle
// reimplements the thirty pairs from production text: trim + take(40),
// |/^ escaping, prefix + "|" + host + suffix; parse is the inverse
// with unescape + blank check. formatAssistCircle lives in
// GroupPlayModeMedia (144 covered the other twelve media pairs but
// missed it); its parse lives here, so pair 29 formats via Media.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Java Character.isWhitespace set (28 units); Kotlin trim()/isBlank()
// use exactly this, not the <= 0x20 rule.
private val WS145 = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', '\u0020', '\u00A0', '\u1680', '\u2000', '\u2001',
    '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
    '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

private fun trim145(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i] in WS145) i++
    while (j > i && s[j - 1] in WS145) j--
    return s.substring(i, j)
}

private fun isBlank145(s: String): Boolean = s.all { it in WS145 }

private fun esc145(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")

private fun unesc145(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

private val PAIR_TABLE145 = arrayOf(
    "SUMCIRCLE:" to " summary circle",
    "REWRITERELAY:" to " rewrite relay",
    "PROMPTSPRINT:" to " prompt sprint",
    "SUGCIRCLE:" to " suggest circle",
    "REPLYSPRINT:" to " reply sprint",
    "DECISIONDASH:" to " decision dash",
    "DOCHUNT:" to " doc hunt",
    "MEANINGRACE:" to " meaning race",
    "INSIGHTSPRINT:" to " insight sprint",
    "MARKHUNT:" to " mark hunt",
    "WALLPICK:" to " wall pick",
    "FONTRACE:" to " font race",
    "THEMESPRINT:" to " theme sprint",
    "UNREADRUSH:" to " unread rush",
    "ALERTSPRINT:" to " alert sprint",
    "BEEPDASH:" to " beep dash",
    "PUSHRACE:" to " push race",
    "REMINDCIRCLE:" to " remind circle",
    "WAKESPRINT:" to " wake sprint",
    "FALLBACKDASH:" to " fallback dash",
    "CLICKBEAT:" to " click beat",
    "BUZZRELAY:" to " buzz relay",
    "FEELSPRINT:" to " feel sprint",
    "SLIDERACE:" to " slide race",
    "SPRINGDASH:" to " spring dash",
    "SHIELDSPRINT:" to " shield sprint",
    "PQXDHDASH:" to " pqxdh dash",
    "MARKSPRINT:" to " mark sprint",
    "STAMPRELAY:" to " stamp relay",
    "ASSISTCIRCLE:" to " assist circle",
)

private fun formatProduction145(pair: Int, mode: String, host: String): String = when (pair) {
    0 -> GroupPlayModeSystem.formatSummaryCircle(mode, host)
    1 -> GroupPlayModeSystem.formatRewriteRelay(mode, host)
    2 -> GroupPlayModeSystem.formatPromptSprint(mode, host)
    3 -> GroupPlayModeSystem.formatSuggestCircle(mode, host)
    4 -> GroupPlayModeSystem.formatReplySprint(mode, host)
    5 -> GroupPlayModeSystem.formatDecisionDash(mode, host)
    6 -> GroupPlayModeSystem.formatDocHunt(mode, host)
    7 -> GroupPlayModeSystem.formatMeaningRace(mode, host)
    8 -> GroupPlayModeSystem.formatInsightSprint(mode, host)
    9 -> GroupPlayModeSystem.formatMarkHunt(mode, host)
    10 -> GroupPlayModeSystem.formatWallPick(mode, host)
    11 -> GroupPlayModeSystem.formatFontRace(mode, host)
    12 -> GroupPlayModeSystem.formatThemeSprint(mode, host)
    13 -> GroupPlayModeSystem.formatUnreadRush(mode, host)
    14 -> GroupPlayModeSystem.formatAlertSprint(mode, host)
    15 -> GroupPlayModeSystem.formatBeepDash(mode, host)
    16 -> GroupPlayModeSystem.formatPushRace(mode, host)
    17 -> GroupPlayModeSystem.formatRemindCircle(mode, host)
    18 -> GroupPlayModeSystem.formatWakeSprint(mode, host)
    19 -> GroupPlayModeSystem.formatFallbackDash(mode, host)
    20 -> GroupPlayModeSystem.formatClickBeat(mode, host)
    21 -> GroupPlayModeSystem.formatBuzzRelay(mode, host)
    22 -> GroupPlayModeSystem.formatFeelSprint(mode, host)
    23 -> GroupPlayModeSystem.formatSlideRace(mode, host)
    24 -> GroupPlayModeSystem.formatSpringDash(mode, host)
    25 -> GroupPlayModeSystem.formatShieldSprint(mode, host)
    26 -> GroupPlayModeSystem.formatPqxdhDash(mode, host)
    27 -> GroupPlayModeSystem.formatMarkSprint(mode, host)
    28 -> GroupPlayModeSystem.formatStampRelay(mode, host)
    29 -> GroupPlayModeMedia.formatAssistCircle(mode, host)
    else -> error("bad pair $pair")
}

private fun parseProduction145(pair: Int, content: String): String? = when (pair) {
    0 -> GroupPlayModeSystem.parseSummaryCircle(content)
    1 -> GroupPlayModeSystem.parseRewriteRelay(content)
    2 -> GroupPlayModeSystem.parsePromptSprint(content)
    3 -> GroupPlayModeSystem.parseSuggestCircle(content)
    4 -> GroupPlayModeSystem.parseReplySprint(content)
    5 -> GroupPlayModeSystem.parseDecisionDash(content)
    6 -> GroupPlayModeSystem.parseDocHunt(content)
    7 -> GroupPlayModeSystem.parseMeaningRace(content)
    8 -> GroupPlayModeSystem.parseInsightSprint(content)
    9 -> GroupPlayModeSystem.parseMarkHunt(content)
    10 -> GroupPlayModeSystem.parseWallPick(content)
    11 -> GroupPlayModeSystem.parseFontRace(content)
    12 -> GroupPlayModeSystem.parseThemeSprint(content)
    13 -> GroupPlayModeSystem.parseUnreadRush(content)
    14 -> GroupPlayModeSystem.parseAlertSprint(content)
    15 -> GroupPlayModeSystem.parseBeepDash(content)
    16 -> GroupPlayModeSystem.parsePushRace(content)
    17 -> GroupPlayModeSystem.parseRemindCircle(content)
    18 -> GroupPlayModeSystem.parseWakeSprint(content)
    19 -> GroupPlayModeSystem.parseFallbackDash(content)
    20 -> GroupPlayModeSystem.parseClickBeat(content)
    21 -> GroupPlayModeSystem.parseBuzzRelay(content)
    22 -> GroupPlayModeSystem.parseFeelSprint(content)
    23 -> GroupPlayModeSystem.parseSlideRace(content)
    24 -> GroupPlayModeSystem.parseSpringDash(content)
    25 -> GroupPlayModeSystem.parseShieldSprint(content)
    26 -> GroupPlayModeSystem.parsePqxdhDash(content)
    27 -> GroupPlayModeSystem.parseMarkSprint(content)
    28 -> GroupPlayModeSystem.parseStampRelay(content)
    29 -> GroupPlayModeSystem.parseAssistCircle(content)
    else -> error("bad pair $pair")
}

private fun formatOracle145(pair: Int, mode: String, host: String): String {
    val (prefix, suffix) = PAIR_TABLE145[pair]
    val trimmed = trim145(mode)
    val m = if (trimmed.length > 40) trimmed.substring(0, 40) else trimmed
    return prefix + esc145(m) + "|" + host + suffix
}

private fun parseOracle145(pair: Int, content: String): String? {
    val (prefix, _) = PAIR_TABLE145[pair]
    if (!content.startsWith(prefix)) return null
    val body = content.substring(prefix.length)
    val pipe = body.indexOf('|')
    val before = if (pipe < 0) body else body.substring(0, pipe)
    val un = unesc145(before)
    return if (isBlank145(un)) null else un
}

// ---------- generators (mirrored by Oracle145 GEN, seed 145001) ----------

private val MODE_WORD_POOL145 = arrayOf(
    "", " ", "  x  ", "summary", "a|b", "a^b", "\u0001", "\u0002", "|", "^", "||",
    "a".repeat(39), "a".repeat(40), "a".repeat(41), "é", "x".repeat(100),
    "\t\n", " a b ", "A|B^C", "…", "ab", "  ", "x|",
)
private val HOST_POOL145 = arrayOf(
    "", "host", "Alice", "a|b", "x".repeat(50), " summary circle", "SUMCIRCLE:",
    " ", "é", "a^b", "\u0001x", "bob|", "  ",
)

private fun randomMode145(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 12)
    repeat(len) {
        when (rnd.nextInt(5)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.append('0' + rnd.nextInt(10))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            3 -> sb.append(MODE_WORD_POOL145[rnd.nextInt(MODE_WORD_POOL145.size)])
            else -> sb.append(WS145.elementAt(rnd.nextInt(WS145.size)))
        }
    }
    return sb.toString()
}

private fun randomHost145(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 8)
    repeat(len) {
        when (rnd.nextInt(4)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            2 -> sb.append(HOST_POOL145[rnd.nextInt(HOST_POOL145.size)])
            else -> sb.append(WS145.elementAt(rnd.nextInt(WS145.size)))
        }
    }
    return sb.toString()
}

private fun randomContent145(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> formatOracle145(rnd.nextInt(30), randomMode145(rnd), randomHost145(rnd))
    1 -> {
        val (prefix, _) = PAIR_TABLE145[rnd.nextInt(30)]
        prefix + randomMode145(rnd)
    }
    2 -> {
        val (prefix, _) = PAIR_TABLE145[rnd.nextInt(30)]
        val cut = if (prefix.length > 1) rnd.nextInt(1, prefix.length) else 0
        prefix.substring(0, cut) + randomMode145(rnd)
    }
    else -> randomMode145(rnd)
}

class GroupPlayModeSystemFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            Triple(0, "", ""),
            Triple(0, "  x  ", "host"),
            Triple(0, "a".repeat(39), "h"),
            Triple(0, "a".repeat(40), "h"),
            Triple(0, "a".repeat(41), "h"),
            Triple(0, "a|b^c", "h"),
            Triple(0, "\u0001^|", "h"),
            Triple(0, "   ", "h"),
            Triple(0, "\t\n x \u00A0", "h"),
            Triple(0, "x", "a|b"),
            Triple(0, "x", ""),
            Triple(5, "é".repeat(50), "h"),
            Triple(28, "a".repeat(100), "h".repeat(100)),
            Triple(29, "assist", "h"),
            Triple(29, "a|b^c", "h"),
        )
        for ((pair, mode, host) in cases) {
            assertEquals(formatOracle145(pair, mode, host), formatProduction145(pair, mode, host))
            val formatted = formatProduction145(pair, mode, host)
            assertEquals(parseOracle145(pair, formatted), parseProduction145(pair, formatted))
        }
        val parseCases = listOf(
            0 to "SUMCIRCLE:",
            0 to "SUMCIRCLE: ",
            0 to "SUMCIRCLE:  |host summary circle",
            0 to "SUMCIRCLE:a|host summary circle",
            0 to "SUMCIRCLE:\u0001|host summary circle",
            0 to "SUMCIRCLE:\u0002|host summary circle",
            0 to "SUMCIRCLE:\u00A0|host summary circle",
            0 to "sumcircle:a|host summary circle",
            0 to "REWRITERELAY:a|host rewrite relay",
            0 to "SUMCIRCLE",
            0 to "",
            0 to "SUMCIRCLE:a",
            0 to "SUMCIRCLE:a|b|c",
            5 to "DECISIONDASH:x|host decision dash",
            5 to "DECISIONDASH: |host decision dash",
            29 to "ASSISTCIRCLE:a|host assist circle",
            29 to "ASSISTCIRCLE: |host assist circle",
            29 to "ASSISTCIRCLE:",
        )
        for ((pair, content) in parseCases) {
            assertEquals(parseOracle145(pair, content), parseProduction145(pair, content))
        }
        assertNull(parseProduction145(29, "SUMCIRCLE:a|host summary circle"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(145001)
        repeat(1500) {
            val pair = rnd.nextInt(30)
            val mode = randomMode145(rnd)
            val host = randomHost145(rnd)
            assertEquals(formatOracle145(pair, mode, host), formatProduction145(pair, mode, host))
            val content = randomContent145(rnd)
            val p2 = rnd.nextInt(30)
            assertEquals(parseOracle145(p2, content), parseProduction145(p2, content))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Single-unit modes/hosts across ASCII + whitespace + boundary units.
        val units = (0..0x7F).toList() + WS145.map { it.code } +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (pair in 0 until 30) {
            for (u in units) {
                val mode = String(charArrayOf(u.toChar()))
                assertEquals(formatOracle145(pair, mode, "h"), formatProduction145(pair, mode, "h"))
                assertEquals(formatOracle145(pair, "m", mode), formatProduction145(pair, "m", mode))
            }
        }
        // Length sweep around the take(40) cut for four load-bearing chars.
        for (pair in 0 until 30) {
            for (c in listOf('a', '|', '^', ' ')) {
                for (len in 0..45) {
                    val mode = c.toString().repeat(len)
                    assertEquals(formatOracle145(pair, mode, "h"), formatProduction145(pair, mode, "h"))
                }
            }
        }
        // Parse sweep: prefix + one unit + "|x", and prefix + runs.
        for (pair in 0 until 30) {
            val (prefix, _) = PAIR_TABLE145[pair]
            for (u in units) {
                val content = prefix + String(charArrayOf(u.toChar())) + "|x"
                assertEquals(parseOracle145(pair, content), parseProduction145(pair, content))
            }
            for (len in 0..45) {
                val content = prefix + "a".repeat(len) + "|x"
                assertEquals(parseOracle145(pair, content), parseProduction145(pair, content))
            }
        }
    }
}
