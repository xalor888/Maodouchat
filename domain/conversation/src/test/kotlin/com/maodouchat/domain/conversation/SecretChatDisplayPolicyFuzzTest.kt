// Fuzz / random-oracle tests for SecretChatDisplayPolicy
// (checklist line 931, 139th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a secret chat
// is excluded from the "all chats" list, and its display name is always
// the fixed label "密聊" -- the raw name is trimmed and checked for
// emptiness, but both branches return the same label, so the input can
// never change the result. The differential still pins that: the
// positive control's trim-and-return mutant is caught on non-blank input.
// No fuzz test covered this object before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files for SecretChatDisplayPolicy / excludeFromAllChats /
// mosaicDisplayName).
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

private fun excludeFromAllChatsOracle(isSecret: Boolean): Boolean = isSecret

private fun mosaicDisplayNameOracle(@Suppress("UNUSED_PARAMETER") raw: String?): String = "密聊"

// ---------- generators (mirrored by Oracle139 GEN, seed 139003) ----------

private val SCD_RAW_POOL = arrayOf(
    "", " ", "密聊", "张三", "  Alice  ", "\u00A0",
    "a\uD800b", "\uD83D\uDE00", "x", "😀昵称"
)

private fun genScdRaw(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> SCD_RAW_POOL[rnd.nextInt(SCD_RAW_POOL.size)]
    2 -> "a".repeat(rnd.nextInt(0, 13))
    else -> randomText(rnd, 10)
}

private data class ScdCase(
    val isSecret: Boolean,
    val raw: String?,
)

private fun genScdCase(rnd: Random): ScdCase =
    ScdCase(isSecret = rnd.nextBoolean(), raw = genScdRaw(rnd))

class SecretChatDisplayPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Exclusion follows the flag exactly.
        assertTrue(SecretChatDisplayPolicy.excludeFromAllChats(isSecret = true))
        assertFalse(SecretChatDisplayPolicy.excludeFromAllChats(isSecret = false))
        // The display name is the fixed label for every input: a real
        // name, a blank name, the label itself, null, and a long name.
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("张三"))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("  Alice  "))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("密聊"))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("   "))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName(""))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName(null))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("x".repeat(500)))
        assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName("😀昵称"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle139.genScd consumption order exactly, so seed
        // 139003 replays the same 1500 GEN lines drive139.py ran.
        val rnd = Random(139003)
        repeat(1500) {
            val c = genScdCase(rnd)
            assertEquals(
                excludeFromAllChatsOracle(c.isSecret),
                SecretChatDisplayPolicy.excludeFromAllChats(c.isSecret)
            )
            assertEquals(
                mosaicDisplayNameOracle(c.raw),
                SecretChatDisplayPolicy.mosaicDisplayName(c.raw)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Both flags x every pool raw, plus null.
        for (isSecret in listOf(false, true)) {
            assertEquals(
                excludeFromAllChatsOracle(isSecret),
                SecretChatDisplayPolicy.excludeFromAllChats(isSecret)
            )
            for (raw in listOf<String?>(null) + SCD_RAW_POOL) {
                assertEquals(
                    mosaicDisplayNameOracle(raw),
                    SecretChatDisplayPolicy.mosaicDisplayName(raw)
                )
            }
        }
        // Every single low unit as a 1-char raw name, both flags.
        for (code in 0x00..0x7F) {
            val ch = code.toChar().toString()
            assertEquals("密聊", SecretChatDisplayPolicy.mosaicDisplayName(ch))
        }
    }
}
