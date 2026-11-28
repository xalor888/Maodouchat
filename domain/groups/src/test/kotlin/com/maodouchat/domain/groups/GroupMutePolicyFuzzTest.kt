package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [GroupMutePolicy] (checklist line 931, 32nd block).
 * Test-only, zero production changes. The oracle below re-implements the
 * add-then-cap arithmetic by hand; a Python mini-model differentially checks
 * the oracle transcription against an independent production transcription.
 * Note: mutedUntil adds with wrapping Long arithmetic, so a now very close to
 * Long.MAX_VALUE wraps the cap below the duration sum and the result goes
 * negative (pinned as current behaviour, not changed).
 */
class GroupMutePolicyFuzzTest {

    private fun oracleMutedUntil(nowMs: Long, preset: GroupMutePreset): Long {
        val until = nowMs + preset.durationMs // wraps exactly like production
        val maxUntil = nowMs + GroupMutePolicy.MAX_MUTE_MS
        return minOf(until, maxUntil)
    }

    // now values where now+duration and now+MAX cannot wrap, so the cap is a real cap.
    private fun randomSafeNow(r: Random): Long =
        if (r.nextInt(10) < 7) r.nextLong(0L, Long.MAX_VALUE - GroupMutePolicy.MAX_MUTE_MS)
        else listOf(0L, 1L, 1_000_000L, Long.MAX_VALUE - GroupMutePolicy.MAX_MUTE_MS - 1)[r.nextInt(4)]

    @Test
    fun randomMutedUntilMatchesOracle() {
        val r = Random(3221)
        val presets = GroupMutePreset.entries
        repeat(300) {
            val now = randomSafeNow(r)
            val preset = presets[r.nextInt(presets.size)]
            val until = GroupMutePolicy.mutedUntil(now, preset)
            assertEquals(oracleMutedUntil(now, preset), until, "now=$now preset=$preset")
            assertTrue(until > now, "mutedUntil is strictly after now: now=$now preset=$preset until=$until")
            assertTrue(until <= now + GroupMutePolicy.MAX_MUTE_MS)
            assertTrue(GroupMutePolicy.isActiveMute(until, now))
        }
    }

    @Test
    fun presetsAreOrderedAndCapped() {
        val presets = GroupMutePolicy.presets
        assertEquals(11, presets.size)
        assertEquals(GroupMutePreset.entries, presets)
        var previous = 0L
        presets.forEach { preset ->
            assertTrue(preset.durationMs > previous, "presets strictly increasing: $preset")
            assertTrue(preset.durationMs > 0L)
            assertTrue(preset.durationMs <= GroupMutePolicy.MAX_MUTE_MS)
            previous = preset.durationMs
        }
        assertEquals(GroupMutePolicy.MAX_MUTE_MS, GroupMutePreset.DAYS_30.durationMs)
        // A short preset adds exactly; the longest preset lands exactly on the cap.
        val now = 1_000_000L
        assertEquals(now + 3_600_000L, GroupMutePolicy.mutedUntil(now, GroupMutePreset.HOUR_1))
        assertEquals(now + GroupMutePolicy.MAX_MUTE_MS, GroupMutePolicy.mutedUntil(now, GroupMutePreset.DAYS_30))
    }

    @Test
    fun randomActiveMuteMatchesComparison() {
        val r = Random(3223)
        repeat(300) {
            val now = r.nextLong()
            val mutedUntil = if (r.nextInt(10) == 0) now else r.nextLong()
            assertEquals(
                mutedUntil > now,
                GroupMutePolicy.isActiveMute(mutedUntil, now),
                "mutedUntil=$mutedUntil now=$now"
            )
        }
        assertEquals(0L, GroupMutePolicy.clearMuteUntil())
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), 5_000_000L))
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), 0L))
        // A cleared mute is only "active" before the epoch, which no real now reaches.
        assertTrue(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), -1L))
    }

    @Test
    fun overflowPins() {
        // now+MAX wraps below now+duration here, so the cap itself is the wrapped value.
        val nearMax = Long.MAX_VALUE - 100_000L
        assertEquals(
            -9_223_372_036_851_275_809L,
            GroupMutePolicy.mutedUntil(nearMax, GroupMutePreset.HOUR_1)
        )
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.mutedUntil(nearMax, GroupMutePreset.HOUR_1), nearMax))
        // At Long.MAX_VALUE both sums wrap; the smaller wrapped value wins.
        assertEquals(
            -9_223_372_036_854_475_809L,
            GroupMutePolicy.mutedUntil(Long.MAX_VALUE, GroupMutePreset.MINUTES_5)
        )
        assertEquals(
            -9_223_372_034_262_775_809L,
            GroupMutePolicy.mutedUntil(Long.MAX_VALUE, GroupMutePreset.DAYS_30)
        )
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.mutedUntil(Long.MAX_VALUE, GroupMutePreset.DAYS_30), Long.MAX_VALUE))
    }
}
