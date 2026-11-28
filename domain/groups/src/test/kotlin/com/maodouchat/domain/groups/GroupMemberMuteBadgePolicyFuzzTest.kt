// Fuzz / random-oracle tests for GroupMemberMuteBadgePolicy
// (checklist line 931, 134th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the mute badge
// shows only when muted and the role is not exactly "OWNER". Role
// equality is exact: case, spacing, and any extra unit all matter.
// No GroupMemberMuteBadgePolicy fuzz test existed before this block
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

private fun gmmbMuteOracle(isMuted: Boolean, role: String): Boolean =
    isMuted && role != "OWNER"

// ---------- generators (mirrored by Oracle134 GEN, seed 134003) ----------

private val GMMB_ROLE_POOL = arrayOf(
    "", " ", "OWNER", "owner", "Owner", "oWNER", "ADMIN", "MEMBER",
    "OWNER ", " OWNER", "OWNERX", "XOWNER", "OWNE", "OWNERS", "OWNER\u0000",
    "\u0000OWNER", "MODERATOR", "owner ", " OWNER", "a\uD800b", "\uD83D\uDE00",
    "GUEST", "OWNER\n", "\u00A0"
)

private fun genGmmbRole(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GMMB_ROLE_POOL[rnd.nextInt(GMMB_ROLE_POOL.size)]
    else randomText(rnd, 8)

class GroupMemberMuteBadgePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Muted non-owners show the badge; the owner never does.
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "MEMBER"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "ADMIN"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "MODERATOR"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "GUEST"))
        assertEquals(false, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNER"))
        // Unmuted members never show it, whatever the role.
        assertEquals(false, GroupMemberMuteBadgePolicy.shouldShowMute(false, "MEMBER"))
        assertEquals(false, GroupMemberMuteBadgePolicy.shouldShowMute(false, "ADMIN"))
        assertEquals(false, GroupMemberMuteBadgePolicy.shouldShowMute(false, "OWNER"))
        assertEquals(false, GroupMemberMuteBadgePolicy.shouldShowMute(false, "owner"))
        // Role equality is exact: case, spacing, and affixes all miss "OWNER".
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "owner"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "Owner"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "oWNER"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNER "))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, " OWNER"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNERX"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "XOWNER"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNE"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNERS"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNER\u0000"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "\u0000OWNER"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNER\n"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, ""))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, " "))
        // Lone surrogates and astral text are just units; they miss too.
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "a\uD800b"))
        assertEquals(true, GroupMemberMuteBadgePolicy.shouldShowMute(true, "\uD83D\uDE00"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle134.genGmmb consumption order exactly, so seed
        // 134003 replays the same 1500 GEN lines drive134.py ran.
        val rnd = Random(134003)
        repeat(1500) {
            val isMuted = rnd.nextBoolean()
            val role = genGmmbRole(rnd)
            assertEquals(
                gmmbMuteOracle(isMuted, role),
                GroupMemberMuteBadgePolicy.shouldShowMute(isMuted, role)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val target = "OWNER"
        // Every pool role under both mute states.
        for (role in GMMB_ROLE_POOL) {
            for (isMuted in listOf(false, true)) {
                assertEquals(
                    gmmbMuteOracle(isMuted, role),
                    GroupMemberMuteBadgePolicy.shouldShowMute(isMuted, role)
                )
            }
        }
        // One-unit edits of the target: substitute, delete, insert, flip case.
        val edits = " _Xo0\u0000"
        for (pos in target.indices) {
            for (rep in edits) {
                val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                assertEquals(gmmbMuteOracle(true, s), GroupMemberMuteBadgePolicy.shouldShowMute(true, s))
                assertEquals(gmmbMuteOracle(false, s), GroupMemberMuteBadgePolicy.shouldShowMute(false, s))
            }
            val s = target.removeRange(pos, pos + 1)
            assertEquals(gmmbMuteOracle(true, s), GroupMemberMuteBadgePolicy.shouldShowMute(true, s))
            val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
            val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
            assertEquals(gmmbMuteOracle(true, f), GroupMemberMuteBadgePolicy.shouldShowMute(true, f))
        }
        for (pos in 0..target.length) {
            for (ins in listOf('_', 'X')) {
                val s = target.substring(0, pos) + ins + target.substring(pos)
                assertEquals(gmmbMuteOracle(true, s), GroupMemberMuteBadgePolicy.shouldShowMute(true, s))
            }
        }
        // Every single low unit as the whole role, under both mute states.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            assertEquals(gmmbMuteOracle(true, s), GroupMemberMuteBadgePolicy.shouldShowMute(true, s))
            assertEquals(gmmbMuteOracle(false, s), GroupMemberMuteBadgePolicy.shouldShowMute(false, s))
        }
    }
}
