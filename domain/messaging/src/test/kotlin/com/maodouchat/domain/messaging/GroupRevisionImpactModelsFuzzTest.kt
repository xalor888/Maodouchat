// Fuzz / random-oracle tests for GroupRevisionImpactModels
// (checklist line 931, 129th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: active-chat
// gating, contact-presence gating, the three-way revision impact
// (IGNORE / REFRESH / CURRENT_USER_REMOVED) and the group-key
// invalidation rule are pure string/long comparisons. No
// GroupRevisionImpactModels fuzz test existed before this block.
package com.maodouchat.domain.messaging

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

private val GRI_REMOVAL_REASONS = setOf("MEMBER_REMOVED", "MEMBER_LEFT")

private fun griActiveOracle(active: String, event: String): Boolean =
    active.isNotBlank() && event == active

private fun griPresenceOracle(isGroup: Boolean, contact: String, eventUser: String): Boolean =
    !isGroup && contact.isNotBlank() && contact == eventUser

private fun griImpactOracle(
    active: String,
    current: String,
    event: String,
    target: String?,
    reason: String
): GroupRevisionImpact = when {
    !griActiveOracle(active, event) -> GroupRevisionImpact.IGNORE
    current.isNotBlank() && target == current && reason in GRI_REMOVAL_REASONS ->
        GroupRevisionImpact.CURRENT_USER_REMOVED
    else -> GroupRevisionImpact.REFRESH
}

private fun griInvalidateOracle(cur: Long, ev: Long, impact: GroupRevisionImpact): Boolean =
    impact == GroupRevisionImpact.CURRENT_USER_REMOVED || ev > cur

// ---------- generators (mirrored by Oracle129 GEN, seed 129002) ----------

private val GRI_ID_POOL = arrayOf(
    "", " ", "  ", "\u00A0", "\u2003", "chat1", "chat2", "u1", "u2",
    "alice", "MEMBER_REMOVED", "MEMBER_LEFT", "a\uD800b", "\uDC00", "c1"
)

private val GRI_REASON_POOL = arrayOf(
    "MEMBER_REMOVED", "MEMBER_LEFT", "MEMBER_ADDED", "", " ",
    "member_removed", "MEMBER_REMOVED ", " MEMBER_REMOVED", "MEMBER_LEFT\t",
    "KICKED", "GROUP_DELETED"
)

private val GRI_REV_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -2, -1, 0, 1, 2, 100, Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private fun genGriId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GRI_ID_POOL[rnd.nextInt(GRI_ID_POOL.size)]
    else randomText(rnd, 12)

private fun genGriNullableId(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else genGriId(rnd)

private fun genGriReason(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GRI_REASON_POOL[rnd.nextInt(GRI_REASON_POOL.size)]
    else randomText(rnd, 16)

private fun genGriRev(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) GRI_REV_POOL[rnd.nextInt(GRI_REV_POOL.size)]
    else rnd.nextLong(-10L, 11L)

private fun genGriImpact(rnd: Random): GroupRevisionImpact =
    GroupRevisionImpact.entries[rnd.nextInt(GroupRevisionImpact.entries.size)]

class GroupRevisionImpactModelsFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // isActiveChatEvent: blank active never matches, even itself;
        // equality is exact, no trimming.
        assertFalse(isActiveChatEvent("", ""))
        assertFalse(isActiveChatEvent(" ", " "))
        assertFalse(isActiveChatEvent("\u00A0", "\u00A0"))
        assertTrue(isActiveChatEvent("c1", "c1"))
        assertFalse(isActiveChatEvent("c1", "c2"))
        assertFalse(isActiveChatEvent("c1", " c1"))
        assertFalse(isActiveChatEvent("c1", "c1 "))
        // shouldApplyContactPresence: group chats never apply; blank
        // contact never applies; otherwise exact id equality.
        assertFalse(shouldApplyContactPresence(true, "u1", "u1"))
        assertFalse(shouldApplyContactPresence(false, "", ""))
        assertFalse(shouldApplyContactPresence(false, " ", " "))
        assertTrue(shouldApplyContactPresence(false, "u1", "u1"))
        assertFalse(shouldApplyContactPresence(false, "u1", "u2"))
        // groupRevisionImpact: inactive chat ignores everything.
        assertEquals(GroupRevisionImpact.IGNORE, groupRevisionImpact("", "u1", "c1", "u1", "MEMBER_REMOVED"))
        assertEquals(GroupRevisionImpact.IGNORE, groupRevisionImpact("c1", "u1", "c2", "u1", "MEMBER_REMOVED"))
        // Current-user removal needs all three: non-blank current id,
        // target == current, and a removal reason.
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, groupRevisionImpact("c1", "u1", "c1", "u1", "MEMBER_REMOVED"))
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, groupRevisionImpact("c1", "u1", "c1", "u1", "MEMBER_LEFT"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "u1", "c1", "u1", "MEMBER_ADDED"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "u1", "c1", "u1", "member_removed"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "u1", "c1", "u1", "MEMBER_REMOVED "))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "u1", "c1", "u2", "MEMBER_REMOVED"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "u1", "c1", null, "MEMBER_REMOVED"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "", "c1", "", "MEMBER_REMOVED"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", " ", "c1", " ", "MEMBER_REMOVED"))
        assertEquals(GroupRevisionImpact.REFRESH, groupRevisionImpact("c1", "u1", "c1", "u1", ""))
        // shouldInvalidateGroupKey: removal always invalidates; otherwise
        // only a strictly newer event revision does.
        assertTrue(shouldInvalidateGroupKey(5, 5, GroupRevisionImpact.CURRENT_USER_REMOVED))
        assertTrue(shouldInvalidateGroupKey(5, 1, GroupRevisionImpact.CURRENT_USER_REMOVED))
        assertFalse(shouldInvalidateGroupKey(5, 5, GroupRevisionImpact.REFRESH))
        assertTrue(shouldInvalidateGroupKey(5, 6, GroupRevisionImpact.REFRESH))
        assertFalse(shouldInvalidateGroupKey(5, 5, GroupRevisionImpact.IGNORE))
        assertTrue(shouldInvalidateGroupKey(Long.MAX_VALUE, Long.MIN_VALUE, GroupRevisionImpact.CURRENT_USER_REMOVED))
        assertFalse(shouldInvalidateGroupKey(Long.MAX_VALUE, Long.MIN_VALUE, GroupRevisionImpact.REFRESH))
        assertTrue(shouldInvalidateGroupKey(Long.MIN_VALUE, Long.MAX_VALUE, GroupRevisionImpact.IGNORE))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle129.genGri consumption order exactly, so seed
        // 129002 replays the same 1500 GEN lines drive129.py ran.
        val rnd = Random(129002)
        repeat(1500) {
            when (rnd.nextInt(4)) {
                0 -> {
                    val a = genGriId(rnd)
                    val e = genGriId(rnd)
                    assertEquals(griActiveOracle(a, e), isActiveChatEvent(a, e))
                }
                1 -> {
                    val g = rnd.nextBoolean()
                    val c = genGriId(rnd)
                    val e = genGriId(rnd)
                    assertEquals(griPresenceOracle(g, c, e), shouldApplyContactPresence(g, c, e))
                }
                2 -> {
                    val a = genGriId(rnd)
                    val cur = genGriId(rnd)
                    val e = genGriId(rnd)
                    val target = genGriNullableId(rnd)
                    val reason = genGriReason(rnd)
                    assertEquals(griImpactOracle(a, cur, e, target, reason), groupRevisionImpact(a, cur, e, target, reason))
                }
                else -> {
                    val cur = genGriRev(rnd)
                    val ev = genGriRev(rnd)
                    val impact = genGriImpact(rnd)
                    assertEquals(griInvalidateOracle(cur, ev, impact), shouldInvalidateGroupKey(cur, ev, impact))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // active x event over the id pool; presence over group x id x id.
        for (a in GRI_ID_POOL) {
            for (e in GRI_ID_POOL) {
                assertEquals(griActiveOracle(a, e), isActiveChatEvent(a, e))
            }
        }
        for (g in listOf(false, true)) {
            for (c in GRI_ID_POOL) {
                for (e in GRI_ID_POOL) {
                    assertEquals(griPresenceOracle(g, c, e), shouldApplyContactPresence(g, c, e))
                }
            }
        }
        // impact over a small universe: active x current x event x
        // target (null + pool) x reason.
        val smallIds = arrayOf("", " ", "c1", "c2", "u1", "u2")
        val smallReasons = arrayOf("MEMBER_REMOVED", "MEMBER_LEFT", "MEMBER_ADDED", "", "member_removed")
        for (a in smallIds) {
            for (cur in smallIds) {
                for (e in smallIds) {
                    for (target in arrayOf<String?>(null, *smallIds)) {
                        for (reason in smallReasons) {
                            assertEquals(griImpactOracle(a, cur, e, target, reason), groupRevisionImpact(a, cur, e, target, reason))
                        }
                    }
                }
            }
        }
        // invalidate over rev pool x rev pool x all three impacts.
        for (cur in GRI_REV_POOL) {
            for (ev in GRI_REV_POOL) {
                for (impact in GroupRevisionImpact.entries) {
                    assertEquals(griInvalidateOracle(cur, ev, impact), shouldInvalidateGroupKey(cur, ev, impact))
                }
            }
        }
        // Whitespace / control singles through the blank checks, and
        // every C0 unit as an id.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(griActiveOracle(s, s), isActiveChatEvent(s, s))
            assertEquals(griPresenceOracle(false, s, s), shouldApplyContactPresence(false, s, s))
            assertEquals(griImpactOracle(s, s, s, s, "MEMBER_REMOVED"), groupRevisionImpact(s, s, s, s, "MEMBER_REMOVED"))
        }
        for (u in 0x00..0x1F) {
            val s = u.toChar().toString()
            assertEquals(griActiveOracle(s, s), isActiveChatEvent(s, s))
            assertEquals(griImpactOracle("c1", s, "c1", s, "MEMBER_REMOVED"), groupRevisionImpact("c1", s, "c1", s, "MEMBER_REMOVED"))
        }
    }
}
