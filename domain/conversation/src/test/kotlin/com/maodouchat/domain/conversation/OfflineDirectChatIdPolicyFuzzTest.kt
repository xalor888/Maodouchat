// Fuzz / random-oracle tests for OfflineDirectChatIdPolicy
// (checklist line 931, 136th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the id is just
// "direct_<owner>_<peer>" or "secret_<owner>_<peer>". "_" inside an id
// is not escaped, so "direct_x"+"y" and "direct"+"x_y" collide by design.
// No OfflineDirectChatIdPolicy fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies -- the #2171 hits are
// the 135th block's next-round candidate list, not coverage).
package com.maodouchat.domain.conversation

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

private fun odcOracle(owner: String, peer: String, isSecret: Boolean): String =
    if (isSecret) "secret_${owner}_${peer}" else "direct_${owner}_${peer}"

// ---------- generators (mirrored by Oracle136 GEN, seed 136001) ----------

private val ID_POOL = arrayOf(
    "", " ", "_", "__", "a", "owner1", "peer2", "direct", "secret",
    "direct_", "secret_", "direct_x", "x_y", "a_b", "owner_1",
    "a\uD800b", "\uD83D\uDE00", "a\nb", "x\u0000y", "  ", "u12345678901234567890"
)

private fun genId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) ID_POOL[rnd.nextInt(ID_POOL.size)]
    else randomText(rnd, 12)

class OfflineDirectChatIdPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        assertEquals("direct_owner1_peer2", OfflineDirectChatIdPolicy.offlineDirectChatId("owner1", "peer2", false))
        assertEquals("secret_owner1_peer2", OfflineDirectChatIdPolicy.offlineDirectChatId("owner1", "peer2", true))
        assertEquals("direct__", OfflineDirectChatIdPolicy.offlineDirectChatId("", "", false))
        assertEquals("secret__", OfflineDirectChatIdPolicy.offlineDirectChatId("", "", true))
        assertEquals("direct_a_b", OfflineDirectChatIdPolicy.offlineDirectChatId("a", "b", false))
        assertEquals("secret_a_b", OfflineDirectChatIdPolicy.offlineDirectChatId("a", "b", true))
        // "_" is not escaped: these two inputs collide by design.
        assertEquals("direct_direct_x_y", OfflineDirectChatIdPolicy.offlineDirectChatId("direct_x", "y", false))
        assertEquals("direct_direct_x_y", OfflineDirectChatIdPolicy.offlineDirectChatId("direct", "x_y", false))
        assertEquals(
            OfflineDirectChatIdPolicy.offlineDirectChatId("direct_x", "y", false),
            OfflineDirectChatIdPolicy.offlineDirectChatId("direct", "x_y", false)
        )
        assertEquals("secret_secret_a_b", OfflineDirectChatIdPolicy.offlineDirectChatId("secret_a", "b", true))
        assertEquals("secret_secret_a_b", OfflineDirectChatIdPolicy.offlineDirectChatId("secret", "a_b", true))
        // Prefixes are plain text: an id that already looks like a prefix
        // just nests inside the template.
        assertEquals("direct_direct_direct", OfflineDirectChatIdPolicy.offlineDirectChatId("direct", "direct", false))
        assertEquals("secret_secret_secret", OfflineDirectChatIdPolicy.offlineDirectChatId("secret", "secret", true))
        assertEquals("direct_a\uD800b_c", OfflineDirectChatIdPolicy.offlineDirectChatId("a\uD800b", "c", false))
        assertEquals("secret_a\uD83D\uDE00_b", OfflineDirectChatIdPolicy.offlineDirectChatId("a\uD83D\uDE00", "b", true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle136.genOdc consumption order exactly, so seed
        // 136001 replays the same 1500 GEN lines drive136.py ran.
        val rnd = Random(136001)
        repeat(1500) {
            val owner = genId(rnd)
            val peer = genId(rnd)
            val secret = rnd.nextBoolean()
            assertEquals(
                odcOracle(owner, peer, secret),
                OfflineDirectChatIdPolicy.offlineDirectChatId(owner, peer, secret)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool owner x every pool peer, both secret flags.
        for (owner in ID_POOL) {
            for (peer in ID_POOL) {
                for (secret in listOf(false, true)) {
                    assertEquals(
                        odcOracle(owner, peer, secret),
                        OfflineDirectChatIdPolicy.offlineDirectChatId(owner, peer, secret)
                    )
                }
            }
        }
        // One-unit edits of "direct" / "secret" as the owner id.
        val edits = " _Xa0\u0000"
        for (target in listOf("direct", "secret")) {
            for (pos in target.indices) {
                for (rep in edits) {
                    val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                    assertEquals(odcOracle(s, "p", false), OfflineDirectChatIdPolicy.offlineDirectChatId(s, "p", false))
                    assertEquals(odcOracle(s, "p", true), OfflineDirectChatIdPolicy.offlineDirectChatId(s, "p", true))
                }
                val s = target.removeRange(pos, pos + 1)
                assertEquals(odcOracle(s, "p", false), OfflineDirectChatIdPolicy.offlineDirectChatId(s, "p", false))
                val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
                val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
                assertEquals(odcOracle(f, "p", true), OfflineDirectChatIdPolicy.offlineDirectChatId(f, "p", true))
            }
            for (pos in 0..target.length) {
                for (ins in listOf('_', 'X')) {
                    val s = target.substring(0, pos) + ins + target.substring(pos)
                    assertEquals(odcOracle(s, "p", false), OfflineDirectChatIdPolicy.offlineDirectChatId(s, "p", false))
                }
            }
        }
        // Every single low unit as owner / peer, both secret flags.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            assertEquals(odcOracle(s, "p", false), OfflineDirectChatIdPolicy.offlineDirectChatId(s, "p", false))
            assertEquals(odcOracle(s, "p", true), OfflineDirectChatIdPolicy.offlineDirectChatId(s, "p", true))
            assertEquals(odcOracle("o", s, false), OfflineDirectChatIdPolicy.offlineDirectChatId("o", s, false))
            assertEquals(odcOracle("o", s, true), OfflineDirectChatIdPolicy.offlineDirectChatId("o", s, true))
        }
    }
}
