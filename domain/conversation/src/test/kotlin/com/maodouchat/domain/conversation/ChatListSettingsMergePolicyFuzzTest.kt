// Fuzz / random-oracle tests for ChatListSettingsMergePolicy
// (checklist line 931, 156th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a null local
// snapshot falls back to server; a local snapshot wins only when its
// settingsUpdatedAt is strictly newer (equal timestamps prefer server).
// The other snapshot fields never affect the decision.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------- oracle ----------

internal fun mergeOracle156(server: SettingsSnapshot, local: SettingsSnapshot?): SettingsSnapshot =
    if (local == null || local.settingsUpdatedAt <= server.settingsUpdatedAt) server else local

// ---------- generators ----------

internal val MERGE_TIMESTAMP_POOL156 = listOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 5L, 99L, 100L,
    Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal fun randomSnapshot156(rnd: Random): SettingsSnapshot = SettingsSnapshot(
    pinnedAt = rnd.nextLong(),
    notificationsMuted = rnd.nextBoolean(),
    archived = rnd.nextBoolean(),
    markedUnread = rnd.nextBoolean(),
    settingsUpdatedAt = when (rnd.nextInt(10)) {
        0 -> MERGE_TIMESTAMP_POOL156[rnd.nextInt(MERGE_TIMESTAMP_POOL156.size)]
        1 -> rnd.nextLong(-10, 11)
        else -> rnd.nextLong()
    },
)

internal fun mergeWire156(server: SettingsSnapshot, local: SettingsSnapshot?): String =
    // Only the timestamps affect the decision, so the wire carries
    // "serverUpdated|localUpdatedOrEmpty" (empty means null local).
    server.settingsUpdatedAt.toString() + "|" + (local?.settingsUpdatedAt?.toString() ?: "")

class ChatListSettingsMergePolicyFuzzTest {

    private fun check(server: SettingsSnapshot, local: SettingsSnapshot?) {
        assertEquals(
            mergeOracle156(server, local),
            ChatListSettingsMergePolicy.merge(server, local),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // (server, local, expected winner: 0 = server, 1 = local)
        val cases = listOf(
            Triple(SettingsSnapshot(1, true, true, true, 5), null, 0),
            Triple(SettingsSnapshot(1, true, true, true, 5), SettingsSnapshot(2, false, false, false, 9), 1),
            // Equal timestamps prefer server (authoritative after confirmed REST).
            Triple(SettingsSnapshot(7, true, false, true, 0), SettingsSnapshot(8, false, true, false, 0), 0),
            Triple(SettingsSnapshot(0, false, false, false, 100), SettingsSnapshot(0, true, true, true, 99), 0),
            Triple(SettingsSnapshot(0, false, false, false, 0), SettingsSnapshot(0, false, false, false, 1), 1),
            Triple(SettingsSnapshot(0, false, false, false, 0), SettingsSnapshot(0, false, false, false, Long.MAX_VALUE), 1),
            Triple(SettingsSnapshot(0, false, false, false, 0), SettingsSnapshot(0, false, false, false, Long.MIN_VALUE), 0),
            Triple(SettingsSnapshot(0, false, false, false, Long.MAX_VALUE), SettingsSnapshot(0, false, false, false, Long.MAX_VALUE), 0),
            Triple(SettingsSnapshot(0, false, false, false, Long.MIN_VALUE), SettingsSnapshot(0, false, false, false, Long.MIN_VALUE), 0),
            Triple(SettingsSnapshot(0, false, false, false, Long.MAX_VALUE), SettingsSnapshot(0, false, false, false, Long.MIN_VALUE), 0),
            Triple(SettingsSnapshot(0, false, false, false, Long.MIN_VALUE), SettingsSnapshot(0, false, false, false, 0), 1),
            Triple(SettingsSnapshot(0, false, false, false, -1), SettingsSnapshot(0, false, false, false, 0), 1),
            Triple(SettingsSnapshot(0, false, false, false, 0), SettingsSnapshot(0, false, false, false, -1), 0),
            // The other fields never decide: flags flipped, same timestamps.
            Triple(SettingsSnapshot(9, true, true, true, 5), SettingsSnapshot(1, false, false, false, 5), 0),
            Triple(SettingsSnapshot(9, true, true, true, 5), SettingsSnapshot(1, false, false, false, 6), 1),
        )
        for ((server, local, winner) in cases) {
            val expected = if (winner == 0) server else local!!
            assertEquals(expected, mergeOracle156(server, local))
            assertTrue(expected === ChatListSettingsMergePolicy.merge(server, local))
            check(server, local)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(156001)
        repeat(2000) {
            val server = randomSnapshot156(rnd)
            val local = if (rnd.nextInt(10) == 0) null else randomSnapshot156(rnd)
            check(server, local)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Timestamp pool x pool, local null and non-null.
        for (s in MERGE_TIMESTAMP_POOL156) {
            val server = SettingsSnapshot(0, false, false, false, s)
            check(server, null)
            for (l in MERGE_TIMESTAMP_POOL156) {
                check(server, SettingsSnapshot(0, false, false, false, l))
            }
        }
        // Flag combinations never decide: same timestamps, all flag mixes.
        for (sFlags in listOf(false, true)) {
            for (lFlags in listOf(false, true)) {
                check(
                    SettingsSnapshot(1, sFlags, sFlags, sFlags, 5),
                    SettingsSnapshot(2, lFlags, lFlags, lFlags, 5),
                )
                check(
                    SettingsSnapshot(1, sFlags, sFlags, sFlags, 5),
                    SettingsSnapshot(2, lFlags, lFlags, lFlags, 6),
                )
            }
        }
    }
}
