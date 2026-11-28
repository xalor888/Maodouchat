// Fuzz / random-oracle tests for OwnerSessionPolicy
// (checklist line 931, 154th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: current only
// when no purge is in progress, the snapshot owner is non-blank and not
// the "me" placeholder, the live user matches the snapshot owner, the
// live token is non-blank, and the live session generation matches.
package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun isCurrentOracle154(
    snapshot: OwnerSessionSnapshot,
    liveUserId: String?,
    liveToken: String?,
    liveSessionGeneration: Long,
    purgeInProgress: Boolean,
): Boolean =
    !purgeInProgress &&
        snapshot.ownerUserId.isNotBlank() &&
        snapshot.ownerUserId != "me" &&
        liveUserId == snapshot.ownerUserId &&
        !liveToken.isNullOrBlank() &&
        liveSessionGeneration == snapshot.sessionGeneration

// ---------- generators ----------

internal data class OwnerCase154(
    val ownerUserId: String,
    val snapshotGeneration: Long,
    val liveUserId: String?,
    val liveToken: String?,
    val liveSessionGeneration: Long,
    val purgeInProgress: Boolean,
)

internal val OWNER_USER_POOL154 = listOf(
    "u1", "u2", "", " ", "me", "Me", "u1 ", " u1", "alice", "u-1",
    "\u00a0", "\u0085", "\u2003", "\u3000", "m", "mee", "me ",
)

internal val OWNER_GEN_POOL154 = listOf(
    0L, 1L, 7L, -1L, Long.MAX_VALUE, Long.MIN_VALUE,
)

internal fun randomOwnerUserId154(rnd: Random): String =
    if (rnd.nextInt(10) < 8) OWNER_USER_POOL154[rnd.nextInt(OWNER_USER_POOL154.size)]
    else buildString { repeat(rnd.nextInt(0, 5)) { append(('a' + rnd.nextInt(26))) } }

internal fun randomGeneration154(rnd: Random): Long =
    if (rnd.nextInt(10) < 7) OWNER_GEN_POOL154[rnd.nextInt(OWNER_GEN_POOL154.size)]
    else rnd.nextLong(-10L, 11L)

internal fun randomOwnerCase154(rnd: Random): OwnerCase154 {
    val owner = randomOwnerUserId154(rnd)
    val snapGen = randomGeneration154(rnd)
    val liveUser = if (rnd.nextInt(10) < 6) owner else randomOwnerUserId154(rnd)
    val liveUserOrNull = if (rnd.nextInt(10) < 1) null else liveUser
    val token = when (rnd.nextInt(10)) {
        0 -> null
        1 -> listOf("", " ", "\u00a0", "\u0085")[rnd.nextInt(4)]
        else -> "tok" + rnd.nextInt(100)
    }
    val liveGen = if (rnd.nextInt(10) < 6) snapGen else randomGeneration154(rnd)
    return OwnerCase154(
        ownerUserId = owner,
        snapshotGeneration = snapGen,
        liveUserId = liveUserOrNull,
        liveToken = token,
        liveSessionGeneration = liveGen,
        purgeInProgress = rnd.nextInt(10) == 0,
    )
}

internal fun jsonEscape154(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun ownerCaseWireJson154(case: OwnerCase154): String {
    val sb = StringBuilder()
    sb.append("{\"o\":\"").append(jsonEscape154(case.ownerUserId)).append("\"")
    sb.append(",\"sg\":").append(case.snapshotGeneration)
    if (case.liveUserId != null) sb.append(",\"lu\":\"").append(jsonEscape154(case.liveUserId)).append("\"")
    if (case.liveToken != null) sb.append(",\"lt\":\"").append(jsonEscape154(case.liveToken)).append("\"")
    sb.append(",\"lg\":").append(case.liveSessionGeneration)
    sb.append(",\"pp\":").append(if (case.purgeInProgress) 1 else 0)
    sb.append("}")
    return sb.toString()
}

class OwnerSessionPolicyFuzzTest {

    private fun check(case: OwnerCase154) {
        val snapshot = OwnerSessionSnapshot(case.ownerUserId, case.snapshotGeneration)
        assertEquals(
            isCurrentOracle154(snapshot, case.liveUserId, case.liveToken, case.liveSessionGeneration, case.purgeInProgress),
            OwnerSessionPolicy.isCurrent(snapshot, case.liveUserId, case.liveToken, case.liveSessionGeneration, case.purgeInProgress),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Hand-computed expectations; oracle must agree with both.
        val cases = listOf(
            OwnerCase154("u1", 7L, "u1", "tok", 7L, false) to true,
            OwnerCase154("u1", 7L, "u1", "tok", 7L, true) to false,
            OwnerCase154("", 7L, "u1", "tok", 7L, false) to false,
            OwnerCase154("  ", 7L, "  ", "tok", 7L, false) to false,
            // NBSP is blank in Kotlin; NEL (U+0085) is not.
            OwnerCase154("\u00a0", 7L, "\u00a0", "tok", 7L, false) to false,
            OwnerCase154("\u0085", 7L, "\u0085", "tok", 7L, false) to true,
            OwnerCase154("me", 7L, "me", "tok", 7L, false) to false,
            OwnerCase154("Me", 7L, "Me", "tok", 7L, false) to true,
            OwnerCase154("u1", 7L, null, "tok", 7L, false) to false,
            OwnerCase154("u1", 7L, "u2", "tok", 7L, false) to false,
            OwnerCase154("u1", 7L, "u1", null, 7L, false) to false,
            OwnerCase154("u1", 7L, "u1", "", 7L, false) to false,
            OwnerCase154("u1", 7L, "u1", " ", 7L, false) to false,
            OwnerCase154("u1", 7L, "u1", "\u0085", 7L, false) to true,
            OwnerCase154("u1", 7L, "u1", "tok", 8L, false) to false,
            OwnerCase154("u1", Long.MIN_VALUE, "u1", "tok", Long.MIN_VALUE, false) to true,
            OwnerCase154("u1", Long.MAX_VALUE, "u1", "tok", Long.MAX_VALUE, false) to true,
            // Equality is exact, not trimmed.
            OwnerCase154("u1 ", 7L, "u1 ", "tok", 7L, false) to true,
            OwnerCase154("u1 ", 7L, "u1", "tok", 7L, false) to false,
            OwnerCase154("u1", 7L, "u1 ", "tok", 7L, false) to false,
        )
        for ((case, expected) in cases) {
            val snapshot = OwnerSessionSnapshot(case.ownerUserId, case.snapshotGeneration)
            assertEquals(expected, isCurrentOracle154(snapshot, case.liveUserId, case.liveToken, case.liveSessionGeneration, case.purgeInProgress))
            assertEquals(
                isCurrentOracle154(snapshot, case.liveUserId, case.liveToken, case.liveSessionGeneration, case.purgeInProgress),
                OwnerSessionPolicy.isCurrent(snapshot, case.liveUserId, case.liveToken, case.liveSessionGeneration, case.purgeInProgress),
            )
            check(case)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(154002)
        repeat(2000) {
            check(randomOwnerCase154(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        for (owner in listOf("u1", "u2", "", " ", "me", "Me", "\u00a0", "\u0085", "u1 ")) {
            for (live in listOf<String?>(null, "u1", "u2", "", " ", "me", "Me", "\u00a0", "\u0085", "u1 ")) {
                for (token in listOf<String?>(null, "tok", "", " ", "\u0085")) {
                    for (gen in listOf(7L, 8L)) {
                        for (purge in listOf(false, true)) {
                            check(OwnerCase154(owner, 7L, live, token, gen, purge))
                        }
                    }
                }
            }
        }
        // Generation grid: snapshot x live over the Long edges.
        for (snapGen in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 7L, Long.MAX_VALUE)) {
            for (liveGen in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 7L, Long.MAX_VALUE)) {
                check(OwnerCase154("u1", snapGen, "u1", "tok", liveGen, false))
            }
        }
    }
}
