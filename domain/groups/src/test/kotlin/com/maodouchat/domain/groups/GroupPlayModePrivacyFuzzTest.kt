// GroupPlayModePrivacy direct pairs (145th block). Oracle
// reimplements the fifteen pairs from production text: trim + take(40),
// |/^ escaping, prefix + "|" + host + suffix; parse is the inverse
// with unescape + blank check. The twelve Seal-delegated pairs live
// in GroupPlaySealPolicy (already fuzzed in block 15) and are not
// repeated here.
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
    "LEAKSPRINT:" to " leak sprint",
    "PREVIEWMASK:" to " preview mask",
    "QUIETHOUR:" to " quiet hour",
    "OFFLINEHINT:" to " offline hint",
    "FADECIRCLE:" to " fade circle",
    "RECENTSHIDE:" to " recents hide",
    "COPYLOCK:" to " copy lock",
    "EXPORTSEAL:" to " export seal",
    "LEAKWALL:" to " leak wall",
    "FORWARDSEAL:" to " forward seal",
    "CHATEXPORTLOCK:" to " chat export lock",
    "VAULTFENCE:" to " vault fence",
    "SEALSPRINT:" to " seal sprint",
    "CERTRELAY:" to " cert relay",
    "FADETIMER:" to " fade timer",
)

private fun formatProduction145(pair: Int, mode: String, host: String): String = when (pair) {
    0 -> GroupPlayModePrivacy.formatLeakSprint(mode, host)
    1 -> GroupPlayModePrivacy.formatPreviewMask(mode, host)
    2 -> GroupPlayModePrivacy.formatQuietHour(mode, host)
    3 -> GroupPlayModePrivacy.formatOfflineHint(mode, host)
    4 -> GroupPlayModePrivacy.formatFadeCircle(mode, host)
    5 -> GroupPlayModePrivacy.formatRecentsHide(mode, host)
    6 -> GroupPlayModePrivacy.formatCopyLock(mode, host)
    7 -> GroupPlayModePrivacy.formatExportSeal(mode, host)
    8 -> GroupPlayModePrivacy.formatLeakWall(mode, host)
    9 -> GroupPlayModePrivacy.formatForwardSeal(mode, host)
    10 -> GroupPlayModePrivacy.formatChatExportLock(mode, host)
    11 -> GroupPlayModePrivacy.formatVaultFence(mode, host)
    12 -> GroupPlayModePrivacy.formatSealSprint(mode, host)
    13 -> GroupPlayModePrivacy.formatCertRelay(mode, host)
    14 -> GroupPlayModePrivacy.formatFadeTimer(mode, host)
    else -> error("bad pair $pair")
}

private fun parseProduction145(pair: Int, content: String): String? = when (pair) {
    0 -> GroupPlayModePrivacy.parseLeakSprint(content)
    1 -> GroupPlayModePrivacy.parsePreviewMask(content)
    2 -> GroupPlayModePrivacy.parseQuietHour(content)
    3 -> GroupPlayModePrivacy.parseOfflineHint(content)
    4 -> GroupPlayModePrivacy.parseFadeCircle(content)
    5 -> GroupPlayModePrivacy.parseRecentsHide(content)
    6 -> GroupPlayModePrivacy.parseCopyLock(content)
    7 -> GroupPlayModePrivacy.parseExportSeal(content)
    8 -> GroupPlayModePrivacy.parseLeakWall(content)
    9 -> GroupPlayModePrivacy.parseForwardSeal(content)
    10 -> GroupPlayModePrivacy.parseChatExportLock(content)
    11 -> GroupPlayModePrivacy.parseVaultFence(content)
    12 -> GroupPlayModePrivacy.parseSealSprint(content)
    13 -> GroupPlayModePrivacy.parseCertRelay(content)
    14 -> GroupPlayModePrivacy.parseFadeTimer(content)
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

// ---------- generators (mirrored by Oracle145 GEN, seed 145002) ----------

private val MODE_WORD_POOL145 = arrayOf(
    "", " ", "  x  ", "leak", "a|b", "a^b", "\u0001", "\u0002", "|", "^", "||",
    "a".repeat(39), "a".repeat(40), "a".repeat(41), "é", "x".repeat(100),
    "\t\n", " a b ", "A|B^C", "…", "ab", "  ", "x|",
)
private val HOST_POOL145 = arrayOf(
    "", "host", "Alice", "a|b", "x".repeat(50), " leak sprint", "LEAKSPRINT:",
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
    0 -> formatOracle145(rnd.nextInt(15), randomMode145(rnd), randomHost145(rnd))
    1 -> {
        val (prefix, _) = PAIR_TABLE145[rnd.nextInt(15)]
        prefix + randomMode145(rnd)
    }
    2 -> {
        val (prefix, _) = PAIR_TABLE145[rnd.nextInt(15)]
        val cut = if (prefix.length > 1) rnd.nextInt(1, prefix.length) else 0
        prefix.substring(0, cut) + randomMode145(rnd)
    }
    else -> randomMode145(rnd)
}

class GroupPlayModePrivacyFuzzTest {

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
            Triple(10, "é".repeat(50), "h"),
            Triple(14, "a".repeat(100), "h".repeat(100)),
        )
        for ((pair, mode, host) in cases) {
            assertEquals(formatOracle145(pair, mode, host), formatProduction145(pair, mode, host))
            val formatted = formatProduction145(pair, mode, host)
            assertEquals(parseOracle145(pair, formatted), parseProduction145(pair, formatted))
        }
        val parseCases = listOf(
            0 to "LEAKSPRINT:",
            0 to "LEAKSPRINT: ",
            0 to "LEAKSPRINT:  |host leak sprint",
            0 to "LEAKSPRINT:a|host leak sprint",
            0 to "LEAKSPRINT:\u0001|host leak sprint",
            0 to "LEAKSPRINT:\u0002|host leak sprint",
            0 to "LEAKSPRINT:\u00A0|host leak sprint",
            0 to "leaksprint:a|host leak sprint",
            0 to "PREVIEWMASK:a|host preview mask",
            0 to "LEAKSPRINT",
            0 to "",
            0 to "LEAKSPRINT:a",
            0 to "LEAKSPRINT:a|b|c",
            10 to "CHATEXPORTLOCK:x|host chat export lock",
            10 to "CHATEXPORTLOCK: |host chat export lock",
        )
        for ((pair, content) in parseCases) {
            assertEquals(parseOracle145(pair, content), parseProduction145(pair, content))
        }
        assertNull(parseProduction145(0, "LEAKWALL:a|host leak wall"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(145002)
        repeat(1500) {
            val pair = rnd.nextInt(15)
            val mode = randomMode145(rnd)
            val host = randomHost145(rnd)
            assertEquals(formatOracle145(pair, mode, host), formatProduction145(pair, mode, host))
            val content = randomContent145(rnd)
            val p2 = rnd.nextInt(15)
            assertEquals(parseOracle145(p2, content), parseProduction145(p2, content))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Single-unit modes/hosts across ASCII + whitespace + boundary units.
        val units = (0..0x7F).toList() + WS145.map { it.code } +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (pair in 0 until 15) {
            for (u in units) {
                val mode = String(charArrayOf(u.toChar()))
                assertEquals(formatOracle145(pair, mode, "h"), formatProduction145(pair, mode, "h"))
                assertEquals(formatOracle145(pair, "m", mode), formatProduction145(pair, "m", mode))
            }
        }
        // Length sweep around the take(40) cut for four load-bearing chars.
        for (pair in 0 until 15) {
            for (c in listOf('a', '|', '^', ' ')) {
                for (len in 0..45) {
                    val mode = c.toString().repeat(len)
                    assertEquals(formatOracle145(pair, mode, "h"), formatProduction145(pair, mode, "h"))
                }
            }
        }
        // Parse sweep: prefix + one unit + "|x", and prefix + runs.
        for (pair in 0 until 15) {
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
