// Fuzz / random-oracle tests for EnvelopePrepareRoute
// (checklist line 931, 135th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: group control
// kinds take the multi-recipient route, other group messages take the
// Sender Key route, direct chats take the direct route. The input space
// is two booleans, so the sweep is exhaustive by construction.
// No EnvelopePrepareRoute fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

private fun eprDecideOracle(isGroup: Boolean, isGroupControlKind: Boolean): EnvelopePrepareRoute =
    when {
        isGroup && isGroupControlKind -> EnvelopePrepareRoute.GROUP_CONTROL
        isGroup -> EnvelopePrepareRoute.GROUP_SENDER_KEY
        else -> EnvelopePrepareRoute.DIRECT
    }

class EnvelopePrepareRouteFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The full 2x2 truth table, pinned.
        assertEquals(
            EnvelopePrepareRoute.GROUP_CONTROL,
            decideEnvelopePrepareRoute(true, true)
        )
        assertEquals(
            EnvelopePrepareRoute.GROUP_SENDER_KEY,
            decideEnvelopePrepareRoute(true, false)
        )
        assertEquals(EnvelopePrepareRoute.DIRECT, decideEnvelopePrepareRoute(false, true))
        assertEquals(EnvelopePrepareRoute.DIRECT, decideEnvelopePrepareRoute(false, false))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle135.genEpr consumption order exactly, so seed
        // 135003 replays the same 1500 GEN lines drive135.py ran.
        val rnd = Random(135003)
        repeat(1500) {
            val isGroup = rnd.nextBoolean()
            val isGroupControlKind = rnd.nextBoolean()
            assertEquals(
                eprDecideOracle(isGroup, isGroupControlKind),
                decideEnvelopePrepareRoute(isGroup, isGroupControlKind)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Two booleans in, three routes out: all four combos, each twice.
        for (isGroup in listOf(false, true)) {
            for (isGroupControlKind in listOf(false, true)) {
                repeat(2) {
                    assertEquals(
                        eprDecideOracle(isGroup, isGroupControlKind),
                        decideEnvelopePrepareRoute(isGroup, isGroupControlKind)
                    )
                }
            }
        }
    }
}
