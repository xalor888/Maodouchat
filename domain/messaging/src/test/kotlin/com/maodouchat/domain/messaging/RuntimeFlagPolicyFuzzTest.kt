// Fuzz / random-oracle tests for RuntimeFlagPolicy
// (checklist line 931, 135th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: exactly two
// keys are forced off ("nearby_enabled", "chat_export_enabled"); the
// flag's default value never matters. Key equality is exact: case,
// spacing, and any extra unit all miss.
// No RuntimeFlagPolicy fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Flag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun rfpForcedOracle(key: String): Boolean =
    key == "nearby_enabled" || key == "chat_export_enabled"

// ---------- generators (mirrored by Oracle135 GEN, seed 135004) ----------

private val RFP_KEY_POOL = arrayOf(
    "", " ", "nearby_enabled", "chat_export_enabled",
    "NEARBY_ENABLED", "Nearby_Enabled", "CHAT_EXPORT_ENABLED", "Chat_Export_Enabled",
    "nearby-enabled", "nearby__enabled", "chat__export_enabled",
    "nearby_enable", "nearby_enabledx", "xnearby_enabled",
    "chat_export_enable", "chat_export_enabledx", "xchat_export_enabled",
    "nearby_enabled ", " nearby_enabled", "chat_export_enabled ", " chat_export_enabled",
    "nearby_enabled\u0000", "\u0000nearby_enabled", "nearby", "enabled",
    "chat_export", "export_enabled", "a\uD800b", "\uD83D\uDE00",
    "nearby_enabled\n", "\u00A0"
)

private fun genRfpKey(rnd: Random): String =
    if (rnd.nextInt(2) == 0) RFP_KEY_POOL[rnd.nextInt(RFP_KEY_POOL.size)]
    else randomText(rnd, 14)

class RuntimeFlagPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Exactly the two forced-off keys, whatever the flag default is.
        for (default in listOf(false, true)) {
            assertEquals(true, RuntimeFlagPolicy.isForcedOff(Flag("nearby_enabled", default)))
            assertEquals(true, RuntimeFlagPolicy.isForcedOff(Flag("chat_export_enabled", default)))
        }
        // Key equality is exact: case, spacing, and affixes all miss.
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("NEARBY_ENABLED", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("Nearby_Enabled", false)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("CHAT_EXPORT_ENABLED", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby-enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby__enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby_enable", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby_enabledx", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("xnearby_enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("chat_export_enable", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("chat_export_enabledx", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("xchat_export_enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby_enabled ", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag(" nearby_enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("chat_export_enabled ", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag(" chat_export_enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby_enabled\u0000", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("\u0000nearby_enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("chat_export", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("export_enabled", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag(" ", false)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("nearby_enabled\n", true)))
        // Lone surrogates and astral text are just units; they miss too.
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("a\uD800b", true)))
        assertEquals(false, RuntimeFlagPolicy.isForcedOff(Flag("\uD83D\uDE00", false)))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle135.genRfp consumption order exactly, so seed
        // 135004 replays the same 1500 GEN lines drive135.py ran.
        val rnd = Random(135004)
        repeat(1500) {
            val key = genRfpKey(rnd)
            val default = rnd.nextBoolean()
            assertEquals(rfpForcedOracle(key), RuntimeFlagPolicy.isForcedOff(Flag(key, default)))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool key under both defaults.
        for (key in RFP_KEY_POOL) {
            for (default in listOf(false, true)) {
                assertEquals(
                    rfpForcedOracle(key),
                    RuntimeFlagPolicy.isForcedOff(Flag(key, default))
                )
            }
        }
        // One-unit edits of both forced-off keys: substitute, delete,
        // insert, flip case. The default flips too, to pin it as ignored.
        val targets = listOf("nearby_enabled", "chat_export_enabled")
        val edits = " _Xa0\u0000"
        for (target in targets) {
            for (pos in target.indices) {
                for (rep in edits) {
                    val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                    assertEquals(rfpForcedOracle(s), RuntimeFlagPolicy.isForcedOff(Flag(s, true)))
                    assertEquals(rfpForcedOracle(s), RuntimeFlagPolicy.isForcedOff(Flag(s, false)))
                }
                val s = target.removeRange(pos, pos + 1)
                assertEquals(rfpForcedOracle(s), RuntimeFlagPolicy.isForcedOff(Flag(s, true)))
                val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
                val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
                assertEquals(rfpForcedOracle(f), RuntimeFlagPolicy.isForcedOff(Flag(f, true)))
            }
            for (pos in 0..target.length) {
                for (ins in listOf('_', 'X')) {
                    val s = target.substring(0, pos) + ins + target.substring(pos)
                    assertEquals(rfpForcedOracle(s), RuntimeFlagPolicy.isForcedOff(Flag(s, true)))
                }
            }
        }
        // Every single low unit as the whole key, under both defaults.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            assertEquals(rfpForcedOracle(s), RuntimeFlagPolicy.isForcedOff(Flag(s, true)))
            assertEquals(rfpForcedOracle(s), RuntimeFlagPolicy.isForcedOff(Flag(s, false)))
        }
    }
}
