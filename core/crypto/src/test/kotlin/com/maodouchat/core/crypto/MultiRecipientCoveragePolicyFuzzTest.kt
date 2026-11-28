// Fuzz / random-oracle tests for MultiRecipientCoveragePolicy
// (checklist line 931, 127th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: trim/normalize
// and set coverage over required ids and concrete (user, device)
// targets. isTransient / transientFailure* are NOT covered here
// (exception typed, already unit-tested in
// MultiRecipientCoveragePolicyTest).
package com.maodouchat.core.crypto

import com.maodouchat.core.model.Target
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------- shared oracle ----------

// The probed Kotlin whitespace set (28 UTF-16 code units). Hand-written,
// never calls Char.isWhitespace / String.isBlank().
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

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

private fun isBlankOracle(s: String): Boolean = s.all { it.code in BLANK_UNITS_ORACLE }

private fun trimOracle(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].code in BLANK_UNITS_ORACLE) start++
    while (end > start && s[end - 1].code in BLANK_UNITS_ORACLE) end--
    return s.substring(start, end)
}

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

private fun mrcNormalizeOracle(recipientId: String): String? {
    val t = trimOracle(recipientId)
    return if (isBlankOracle(t)) null else t
}

private data class TargetOracle(val userId: String, val deviceId: Int)

private fun mrcNormalizeTargetOracle(userId: String, deviceId: Int): TargetOracle? {
    val t = trimOracle(userId)
    if (isBlankOracle(t)) return null
    if (deviceId <= 0) return null
    return TargetOracle(t, deviceId)
}

private fun mrcRequiredIdsOracle(ids: List<String>): LinkedHashSet<String> {
    val out = LinkedHashSet<String>()
    for (id in ids) {
        val t = trimOracle(id)
        if (!isBlankOracle(t)) out.add(t)
    }
    return out
}

private fun mrcCoveredIdsOracle(targets: List<TargetOracle>): LinkedHashSet<String> {
    val out = LinkedHashSet<String>()
    for (t in targets) {
        val u = trimOracle(t.userId)
        if (!isBlankOracle(u)) out.add(u)
    }
    return out
}

private fun mrcRequiredTargetsOracle(targets: List<TargetOracle>): LinkedHashSet<TargetOracle> {
    val out = LinkedHashSet<TargetOracle>()
    for (t in targets) {
        val n = mrcNormalizeTargetOracle(t.userId, t.deviceId)
        if (n != null) out.add(n)
    }
    return out
}

private fun mrcRecipientsCoveredOracle(req: List<String>, tgt: List<TargetOracle>): Boolean {
    val required = mrcRequiredIdsOracle(req)
    if (required.isEmpty()) return true
    val covered = mrcCoveredIdsOracle(tgt)
    return required.all { it in covered }
}

private fun mrcTargetsCoveredOracle(req: List<TargetOracle>, tgt: List<TargetOracle>): Boolean {
    val required = mrcRequiredTargetsOracle(req)
    if (required.isEmpty()) return true
    val covered = mrcRequiredTargetsOracle(tgt)
    return required.all { it in covered }
}

private fun mrcMissingRecipientsOracle(req: List<String>, tgt: List<TargetOracle>): List<String> {
    val required = mrcRequiredIdsOracle(req)
    val covered = mrcCoveredIdsOracle(tgt)
    return required.filter { it !in covered }
}

private fun mrcMissingTargetsOracle(req: List<TargetOracle>, tgt: List<TargetOracle>): List<TargetOracle> {
    val required = mrcRequiredTargetsOracle(req)
    val covered = mrcRequiredTargetsOracle(tgt)
    return required.filter { it !in covered }
}

private val MRC_ID_POOL = arrayOf(
    "", " ", "   ", "\u00A0", "alice", "bob", " alice ", "alice ", " alice",
    "a", "u1", "u2", "a\uD800b", "\uDC00", "a\nb", "a\tb", "carol", "dave",
    "ALICE", "alice\u2003"
)

private val MRC_DEV_POOL = intArrayOf(
    -2, -1, 0, 1, 2, 3, 7, 100, Int.MIN_VALUE, Int.MAX_VALUE
)

private fun genMrcId(rnd: Random): String =
    if (rnd.nextInt(3) == 0) MRC_ID_POOL[rnd.nextInt(MRC_ID_POOL.size)] else randomText(rnd, 12)

private fun genMrcDev(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) MRC_DEV_POOL[rnd.nextInt(MRC_DEV_POOL.size)] else rnd.nextInt(-5, 11)

private fun genMrcTarget(rnd: Random): TargetOracle = TargetOracle(genMrcId(rnd), genMrcDev(rnd))

private fun genMrcIdList(rnd: Random): List<String> {
    val n = rnd.nextInt(0, 5)
    val out = ArrayList<String>(n)
    repeat(n) { out.add(genMrcId(rnd)) }
    return out
}

private fun genMrcTargetList(rnd: Random): List<TargetOracle> {
    val n = rnd.nextInt(0, 5)
    val out = ArrayList<TargetOracle>(n)
    repeat(n) { out.add(genMrcTarget(rnd)) }
    return out
}

private fun List<TargetOracle>.toTargets(): List<Target> = map { Target(it.userId, it.deviceId) }

class MultiRecipientCoveragePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // normalize: blank (28-unit set) collapses to null; padding
        // trims; U+0085 is NOT blank, so it survives verbatim.
        assertNull(MultiRecipientCoveragePolicy.normalizeRequiredRecipientId(""))
        assertNull(MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("   "))
        assertNull(MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("\u00A0\u2003"))
        assertEquals("alice", MultiRecipientCoveragePolicy.normalizeRequiredRecipientId(" alice "))
        assertEquals("a\uD800b", MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("a\uD800b"))
        assertEquals("\u0085", MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("\u0085"))
        assertEquals("a", MultiRecipientCoveragePolicy.normalizeRequiredRecipientId("\u2003a\u3000"))
        // User-level coverage ignores device ids entirely (even 0);
        // blank required ids are ignored; empty required is trivially
        // covered.
        assertTrue(MultiRecipientCoveragePolicy.requiredRecipientsCovered(emptyList(), emptyList()))
        assertTrue(MultiRecipientCoveragePolicy.requiredRecipientsCovered(listOf("alice"), listOf(Target(" alice ", 0))))
        assertTrue(MultiRecipientCoveragePolicy.requiredRecipientsCovered(listOf("", " ", "alice"), listOf(Target("alice", 1))))
        assertFalse(MultiRecipientCoveragePolicy.requiredRecipientsCovered(listOf("alice", "bob"), listOf(Target("alice", 1))))
        assertFalse(MultiRecipientCoveragePolicy.requiredRecipientsCovered(listOf("ALICE"), listOf(Target("alice", 1))))
        // Concrete coverage needs a positive device id per target:
        // device 0 / negative never counts, and a padded user id
        // normalizes to the same target.
        assertTrue(MultiRecipientCoveragePolicy.requiredTargetsCovered(
            listOf(Target("alice", 1)), listOf(Target(" alice ", 1))))
        assertFalse(MultiRecipientCoveragePolicy.requiredTargetsCovered(
            listOf(Target("bob", 2)), listOf(Target("bob", 0))))
        assertFalse(MultiRecipientCoveragePolicy.requiredTargetsCovered(
            listOf(Target("bob", 2)), listOf(Target("bob", -1))))
        assertTrue(MultiRecipientCoveragePolicy.requiredTargetsCovered(
            listOf(Target("bob", 2), Target("bob", 2)), listOf(Target("bob", 2))))
        assertTrue(MultiRecipientCoveragePolicy.requiredTargetsCovered(emptyList(), listOf(Target("", 0))))
        // Missing lists keep required insertion order and collapse
        // duplicates; blank required ids never appear.
        assertEquals(
            listOf("bob", "carol"),
            MultiRecipientCoveragePolicy.missingRequiredRecipients(
                listOf("bob", "alice", "carol", "bob", "", " "),
                listOf(Target("alice", 1))
            ).toList()
        )
        assertEquals(
            listOf(Target("bob", 2), Target("carol", 1)),
            MultiRecipientCoveragePolicy.missingRequiredTargets(
                listOf(Target("bob", 2), Target("alice", 1), Target("carol", 1), Target("bob", 2)),
                listOf(Target("alice", 1))
            ).toList()
        )
        assertEquals(
            emptyList<String>(),
            MultiRecipientCoveragePolicy.missingRequiredRecipients(listOf("alice"), listOf(Target("alice", 0))).toList()
        )
        // A zero-device target still counts for user-level missing.
        assertEquals(
            emptyList<Target>(),
            MultiRecipientCoveragePolicy.missingRequiredTargets(
                listOf(Target("alice", 0)), listOf(Target("alice", 1))
            ).toList()
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle127.genMrc consumption order exactly, so seed
        // 127002 replays the same 1500 GEN lines drive127.py ran.
        val rnd = Random(127002)
        repeat(1500) {
            when (rnd.nextInt(5)) {
                0 -> {
                    val id = genMrcId(rnd)
                    assertEquals(mrcNormalizeOracle(id), MultiRecipientCoveragePolicy.normalizeRequiredRecipientId(id))
                }
                1 -> {
                    val req = genMrcIdList(rnd)
                    val tgt = genMrcTargetList(rnd)
                    assertEquals(mrcRecipientsCoveredOracle(req, tgt),
                        MultiRecipientCoveragePolicy.requiredRecipientsCovered(req, tgt.toTargets()))
                }
                2 -> {
                    val req = genMrcTargetList(rnd)
                    val tgt = genMrcTargetList(rnd)
                    assertEquals(mrcTargetsCoveredOracle(req, tgt),
                        MultiRecipientCoveragePolicy.requiredTargetsCovered(req.toTargets(), tgt.toTargets()))
                }
                3 -> {
                    val req = genMrcIdList(rnd)
                    val tgt = genMrcTargetList(rnd)
                    assertEquals(mrcMissingRecipientsOracle(req, tgt),
                        MultiRecipientCoveragePolicy.missingRequiredRecipients(req, tgt.toTargets()).toList())
                }
                else -> {
                    val req = genMrcTargetList(rnd)
                    val tgt = genMrcTargetList(rnd)
                    assertEquals(mrcMissingTargetsOracle(req, tgt).map { Target(it.userId, it.deviceId) },
                        MultiRecipientCoveragePolicy.missingRequiredTargets(req.toTargets(), tgt.toTargets()).toList())
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Small-universe exhaustives, the same inputs drive127.py sweeps.
        val uniIds = arrayOf("alice", "bob", " alice ", "", " ", "carol")
        val uniTgts = arrayOf(
            TargetOracle("alice", 1), TargetOracle("bob", 0), TargetOracle("bob", 2),
            TargetOracle("carol", 1), TargetOracle("", 1), TargetOracle("alice", -1)
        )
        for (id in MRC_ID_POOL) {
            assertEquals(mrcNormalizeOracle(id), MultiRecipientCoveragePolicy.normalizeRequiredRecipientId(id))
        }
        for (r in 0..2) {
            for (reqIds in productIds(uniIds, r)) {
                for (t in 0..2) {
                    for (tgt in productTgts(uniTgts, t)) {
                        assertEquals(mrcRecipientsCoveredOracle(reqIds, tgt),
                            MultiRecipientCoveragePolicy.requiredRecipientsCovered(reqIds, tgt.toTargets()))
                        assertEquals(mrcMissingRecipientsOracle(reqIds, tgt),
                            MultiRecipientCoveragePolicy.missingRequiredRecipients(reqIds, tgt.toTargets()).toList())
                    }
                }
            }
            for (req in productTgts(uniTgts, r)) {
                for (t in 0..2) {
                    for (tgt in productTgts(uniTgts, t)) {
                        assertEquals(mrcTargetsCoveredOracle(req, tgt),
                            MultiRecipientCoveragePolicy.requiredTargetsCovered(req.toTargets(), tgt.toTargets()))
                        assertEquals(mrcMissingTargetsOracle(req, tgt).map { Target(it.userId, it.deviceId) },
                            MultiRecipientCoveragePolicy.missingRequiredTargets(req.toTargets(), tgt.toTargets()).toList())
                    }
                }
            }
        }
    }

    private fun productIds(pool: Array<String>, n: Int): List<List<String>> {
        if (n == 0) return listOf(emptyList())
        val out = ArrayList<List<String>>()
        for (rest in productIds(pool, n - 1)) {
            for (v in pool) out.add(rest + v)
        }
        return out
    }

    private fun productTgts(pool: Array<TargetOracle>, n: Int): List<List<TargetOracle>> {
        if (n == 0) return listOf(emptyList())
        val out = ArrayList<List<TargetOracle>>()
        for (rest in productTgts(pool, n - 1)) {
            for (v in pool) out.add(rest + v)
        }
        return out
    }
}
