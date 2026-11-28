// GroupPlayModePolicy media format/parse pairs (144th block). Oracle
// reimplements the twelve pairs from production text: trim + take(40),
// |/^ escaping, prefix + "|" + host + suffix; parse is the inverse
// with unescape + blank check.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Java Character.isWhitespace set (28 units); Kotlin trim()/isBlank()
// use exactly this, not the <= 0x20 rule.
private val WS144 = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', '\u0020', '\u00A0', '\u1680', '\u2000', '\u2001',
    '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
    '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

private fun trim144(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i] in WS144) i++
    while (j > i && s[j - 1] in WS144) j--
    return s.substring(i, j)
}

private fun isBlank144(s: String): Boolean = s.all { it in WS144 }

private fun esc144(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")

private fun unesc144(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

private val PAIR_TABLE144 = arrayOf(
    "PHOTORACE:" to " photo race",
    "CLIPDASH:" to " clip dash",
    "FRAMEHUNT:" to " frame hunt",
    "VOICERACE:" to " voice race",
    "PIXELQUEST:" to " pixel quest",
    "GIFRELAY:" to " gif relay",
    "VOICERING:" to " voice ring",
    "VIDEOSTAGE:" to " video stage",
    "RINGDASH:" to " ring dash",
    "RINGCHOIR:" to " ring choir",
    "SOUNDWAVE:" to " sound wave",
    "SNAPGUARD:" to " snap guard",
)

private fun formatProduction144(pair: Int, mode: String, host: String): String = when (pair) {
    0 -> GroupPlayModePolicy.formatPhotoRace(mode, host)
    1 -> GroupPlayModePolicy.formatClipDash(mode, host)
    2 -> GroupPlayModePolicy.formatFrameHunt(mode, host)
    3 -> GroupPlayModePolicy.formatVoiceRace(mode, host)
    4 -> GroupPlayModePolicy.formatPixelQuest(mode, host)
    5 -> GroupPlayModePolicy.formatGifRelay(mode, host)
    6 -> GroupPlayModePolicy.formatVoiceRing(mode, host)
    7 -> GroupPlayModePolicy.formatVideoStage(mode, host)
    8 -> GroupPlayModePolicy.formatRingDash(mode, host)
    9 -> GroupPlayModePolicy.formatRingChoir(mode, host)
    10 -> GroupPlayModePolicy.formatSoundWave(mode, host)
    11 -> GroupPlayModePolicy.formatSnapGuard(mode, host)
    else -> error("bad pair $pair")
}

private fun parseProduction144(pair: Int, content: String): String? = when (pair) {
    0 -> GroupPlayModePolicy.parsePhotoRace(content)
    1 -> GroupPlayModePolicy.parseClipDash(content)
    2 -> GroupPlayModePolicy.parseFrameHunt(content)
    3 -> GroupPlayModePolicy.parseVoiceRace(content)
    4 -> GroupPlayModePolicy.parsePixelQuest(content)
    5 -> GroupPlayModePolicy.parseGifRelay(content)
    6 -> GroupPlayModePolicy.parseVoiceRing(content)
    7 -> GroupPlayModePolicy.parseVideoStage(content)
    8 -> GroupPlayModePolicy.parseRingDash(content)
    9 -> GroupPlayModePolicy.parseRingChoir(content)
    10 -> GroupPlayModePolicy.parseSoundWave(content)
    11 -> GroupPlayModePolicy.parseSnapGuard(content)
    else -> error("bad pair $pair")
}

private fun formatOracle144(pair: Int, mode: String, host: String): String {
    val (prefix, suffix) = PAIR_TABLE144[pair]
    val trimmed = trim144(mode)
    val m = if (trimmed.length > 40) trimmed.substring(0, 40) else trimmed
    return prefix + esc144(m) + "|" + host + suffix
}

private fun parseOracle144(pair: Int, content: String): String? {
    val (prefix, _) = PAIR_TABLE144[pair]
    if (!content.startsWith(prefix)) return null
    val body = content.substring(prefix.length)
    val pipe = body.indexOf('|')
    val before = if (pipe < 0) body else body.substring(0, pipe)
    val un = unesc144(before)
    return if (isBlank144(un)) null else un
}

// ---------- generators (mirrored by Oracle144 GEN, seed 144001) ----------

private val MODE_WORD_POOL144 = arrayOf(
    "", " ", "  x  ", "photo", "a|b", "a^b", "\u0001", "\u0002", "|", "^", "||",
    "a".repeat(39), "a".repeat(40), "a".repeat(41), "é", "x".repeat(100),
    "\t\n", " a b ", "A|B^C", "…", "ab", "  ", "x|",
)
private val HOST_POOL144 = arrayOf(
    "", "host", "Alice", "a|b", "x".repeat(50), " photo race", "PHOTORACE:",
    " ", "é", "a^b", "\u0001x", "bob|", "  ",
)

private fun randomMode144(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 12)
    repeat(len) {
        when (rnd.nextInt(5)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.append('0' + rnd.nextInt(10))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            3 -> sb.append(MODE_WORD_POOL144[rnd.nextInt(MODE_WORD_POOL144.size)])
            else -> sb.append(WS144.elementAt(rnd.nextInt(WS144.size)))
        }
    }
    return sb.toString()
}

private fun randomHost144(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 8)
    repeat(len) {
        when (rnd.nextInt(4)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            2 -> sb.append(HOST_POOL144[rnd.nextInt(HOST_POOL144.size)])
            else -> sb.append(WS144.elementAt(rnd.nextInt(WS144.size)))
        }
    }
    return sb.toString()
}

private fun randomContent144(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> formatOracle144(rnd.nextInt(12), randomMode144(rnd), randomHost144(rnd))
    1 -> {
        val (prefix, _) = PAIR_TABLE144[rnd.nextInt(12)]
        prefix + randomMode144(rnd)
    }
    2 -> {
        val (prefix, _) = PAIR_TABLE144[rnd.nextInt(12)]
        val cut = if (prefix.length > 1) rnd.nextInt(1, prefix.length) else 0
        prefix.substring(0, cut) + randomMode144(rnd)
    }
    else -> randomMode144(rnd)
}

class GroupPlayModePolicyFuzzTest {

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
            Triple(11, "a".repeat(100), "h".repeat(100)),
        )
        for ((pair, mode, host) in cases) {
            assertEquals(formatOracle144(pair, mode, host), formatProduction144(pair, mode, host))
            val formatted = formatProduction144(pair, mode, host)
            assertEquals(parseOracle144(pair, formatted), parseProduction144(pair, formatted))
        }
        val parseCases = listOf(
            0 to "PHOTORACE:",
            0 to "PHOTORACE: ",
            0 to "PHOTORACE:  |host photo race",
            0 to "PHOTORACE:a|host photo race",
            0 to "PHOTORACE:\u0001|host photo race",
            0 to "PHOTORACE:\u0002|host photo race",
            0 to "PHOTORACE:\u00A0|host photo race",
            0 to "photorace:a|host photo race",
            0 to "CLIPDASH:a|host clip dash",
            0 to "PHOTORACE",
            0 to "",
            0 to "PHOTORACE:a",
            0 to "PHOTORACE:a|b|c",
            5 to "GIFRELAY:x|host gif relay",
            5 to "GIFRELAY: |host gif relay",
        )
        for ((pair, content) in parseCases) {
            assertEquals(parseOracle144(pair, content), parseProduction144(pair, content))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(144001)
        repeat(1500) {
            val pair = rnd.nextInt(12)
            val mode = randomMode144(rnd)
            val host = randomHost144(rnd)
            assertEquals(formatOracle144(pair, mode, host), formatProduction144(pair, mode, host))
            val content = randomContent144(rnd)
            val p2 = rnd.nextInt(12)
            assertEquals(parseOracle144(p2, content), parseProduction144(p2, content))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Single-unit modes/hosts across ASCII + whitespace + boundary units.
        val units = (0..0x7F).toList() + WS144.map { it.code } +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (pair in 0 until 12) {
            for (u in units) {
                val mode = String(charArrayOf(u.toChar()))
                assertEquals(formatOracle144(pair, mode, "h"), formatProduction144(pair, mode, "h"))
                assertEquals(formatOracle144(pair, "m", mode), formatProduction144(pair, "m", mode))
            }
        }
        // Length sweep around the take(40) cut for four load-bearing chars.
        for (pair in 0 until 12) {
            for (c in listOf('a', '|', '^', ' ')) {
                for (len in 0..45) {
                    val mode = c.toString().repeat(len)
                    assertEquals(formatOracle144(pair, mode, "h"), formatProduction144(pair, mode, "h"))
                }
            }
        }
        // Parse sweep: prefix + one unit + "|x", and prefix + runs.
        for (pair in 0 until 12) {
            val (prefix, _) = PAIR_TABLE144[pair]
            for (u in units) {
                val content = prefix + String(charArrayOf(u.toChar())) + "|x"
                assertEquals(parseOracle144(pair, content), parseProduction144(pair, content))
            }
            for (len in 0..45) {
                val content = prefix + "a".repeat(len) + "|x"
                assertEquals(parseOracle144(pair, content), parseProduction144(pair, content))
            }
        }
    }
}
