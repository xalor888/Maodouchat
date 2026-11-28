// Fuzz / random-oracle tests for GroupAiShareMeta
// (checklist line 931, 133rd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the shared
// message is always the current user's (aiAssisted true, systemIdentity
// false); the mode is trimmed, capped at 40 chars, and blank becomes
// null. No GroupAiShareMeta fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies).
package com.maodouchat.domain.groups

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

private fun gamModeOracle(mode: String?): String? =
    mode?.trim()?.take(40)?.takeIf { it.isNotEmpty() }

private fun gamMetaOracle(mode: String?): Map<String, Any?> = mapOf(
    "aiAssisted" to true,
    "aiAssistantMode" to gamModeOracle(mode),
    "systemIdentity" to false
)

private fun gamFlagOracle(): Boolean = true

// ---------- generators (mirrored by Oracle133 GEN, seed 133002) ----------

private val GAM_MODE_POOL = arrayOf(
    "", " ", "chat", "  chat  ", "voice", "tasks", "  tasks  ", "a",
    "\u00A0", "m\uD800m", "\uD83D\uDE00", "a b", "MODE", "mode",
    "\u2003", "x"
)

private fun genGamMode(rnd: Random): String? {
    if (rnd.nextInt(6) == 0) return null
    if (rnd.nextInt(10) == 0) {
        val padL = rnd.nextInt(0, 3)
        val n = rnd.nextInt(35, 46)
        val padR = rnd.nextInt(0, 3)
        return " ".repeat(padL) + "m".repeat(n) + " ".repeat(padR)
    }
    if (rnd.nextInt(2) == 0) return GAM_MODE_POOL[rnd.nextInt(GAM_MODE_POOL.size)]
    return randomText(rnd, 12)
}

class GroupAiShareMetaFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Blank modes collapse to null; short modes pass through trimmed.
        assertEquals(null, GroupAiShareMeta.shareAssistantMode(null))
        assertEquals(null, GroupAiShareMeta.shareAssistantMode(""))
        assertEquals(null, GroupAiShareMeta.shareAssistantMode("   "))
        assertEquals(null, GroupAiShareMeta.shareAssistantMode("\u00A0"))
        assertEquals("tasks", GroupAiShareMeta.shareAssistantMode("  tasks  "))
        assertEquals("chat", GroupAiShareMeta.shareAssistantMode("chat"))
        // Cap at exactly 40 chars, counted after trimming.
        val mode40 = "m".repeat(40)
        assertEquals(mode40, GroupAiShareMeta.shareAssistantMode(mode40))
        assertEquals(mode40, GroupAiShareMeta.shareAssistantMode("m".repeat(41)))
        assertEquals(mode40, GroupAiShareMeta.shareAssistantMode("m".repeat(100)))
        assertEquals(mode40, GroupAiShareMeta.shareAssistantMode("  " + "m".repeat(41) + "  "))
        // take() counts UTF-16 units: a lone surrogate at the cap edge stays.
        val edge = "m".repeat(39) + "\uD800" + "x"
        assertEquals(41, edge.length)
        assertEquals(
            "m".repeat(39) + "\uD800",
            GroupAiShareMeta.shareAssistantMode(edge)
        )
        // Meta map: always the current user, never a system identity.
        assertEquals(
            mapOf("aiAssisted" to true, "aiAssistantMode" to "tasks", "systemIdentity" to false),
            GroupAiShareMeta.shareAsCurrentUserMeta("tasks")
        )
        assertEquals(
            mapOf("aiAssisted" to true, "aiAssistantMode" to null, "systemIdentity" to false),
            GroupAiShareMeta.shareAsCurrentUserMeta(null)
        )
        assertEquals(
            mapOf("aiAssisted" to true, "aiAssistantMode" to null, "systemIdentity" to false),
            GroupAiShareMeta.shareAsCurrentUserMeta("   ")
        )
        assertEquals(
            mapOf("aiAssisted" to true, "aiAssistantMode" to mode40, "systemIdentity" to false),
            GroupAiShareMeta.shareAsCurrentUserMeta("m".repeat(100))
        )
        assertEquals(true, GroupAiShareMeta.shareAiAssistedFlag())
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle133.genGam consumption order exactly, so seed
        // 133002 replays the same 1500 GEN lines drive133.py ran.
        val rnd = Random(133002)
        repeat(1500) {
            when (rnd.nextInt(3)) {
                0 -> {
                    val mode = genGamMode(rnd)
                    assertEquals(gamModeOracle(mode), GroupAiShareMeta.shareAssistantMode(mode))
                }
                1 -> {
                    val mode = genGamMode(rnd)
                    assertEquals(gamMetaOracle(mode), GroupAiShareMeta.shareAsCurrentUserMeta(mode))
                }
                else -> {
                    assertEquals(gamFlagOracle(), GroupAiShareMeta.shareAiAssistedFlag())
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool mode through both mode and meta.
        for (mode in GAM_MODE_POOL) {
            assertEquals(gamModeOracle(mode), GroupAiShareMeta.shareAssistantMode(mode))
            assertEquals(gamMetaOracle(mode), GroupAiShareMeta.shareAsCurrentUserMeta(mode))
        }
        assertEquals(gamModeOracle(null), GroupAiShareMeta.shareAssistantMode(null))
        assertEquals(gamMetaOracle(null), GroupAiShareMeta.shareAsCurrentUserMeta(null))
        // Cap boundary lengths, with and without surrounding spaces.
        for (n in 38..42) {
            for (pad in listOf("", " ", "  ")) {
                val mode = pad + "m".repeat(n) + pad
                assertEquals(gamModeOracle(mode), GroupAiShareMeta.shareAssistantMode(mode))
                assertEquals(gamMetaOracle(mode), GroupAiShareMeta.shareAsCurrentUserMeta(mode))
            }
        }
        // Whitespace/control singles as modes.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(gamModeOracle(s), GroupAiShareMeta.shareAssistantMode(s))
            assertEquals(gamMetaOracle(s), GroupAiShareMeta.shareAsCurrentUserMeta(s))
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(gamModeOracle(s), GroupAiShareMeta.shareAssistantMode(s))
        }
    }
}
