package com.maodouchat.ui.screen.chatdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMutePolicyTest {

    @Test
    fun presetsArePositiveAndBounded() {
        assertTrue(GroupMutePolicy.presets.isNotEmpty())
        GroupMutePolicy.presets.forEach { preset ->
            assertTrue(preset.durationMs > 0)
            assertTrue(preset.durationMs <= GroupMutePolicy.MAX_MUTE_MS)
        }
    }

    @Test
    fun mutedUntilAddsDuration() {
        val now = 1_000_000L
        val until = GroupMutePolicy.mutedUntil(now, Preset.HOUR_1)
        assertEquals(now + 3_600_000L, until)
    }

    @Test
    fun activeAndClear() {
        val now = 5_000_000L
        assertTrue(GroupMutePolicy.isActiveMute(now + 1, now))
        assertFalse(GroupMutePolicy.isActiveMute(now, now))
        assertEquals(0L, GroupMutePolicy.clearMuteUntil())
    }
    @Test
    fun presetsAreElevenStrictlyIncreasingAndCappedAtMax() {
        assertEquals(11, GroupMutePolicy.presets.size)
        val durations = GroupMutePolicy.presets.map { it.durationMs }
        assertEquals(durations.sorted(), durations)
        assertTrue(durations.zipWithNext().all { (a, b) -> a < b })
        assertEquals(GroupMutePolicy.MAX_MUTE_MS, durations.last())
        assertEquals(300_000L, durations.first())
    }

    @Test
    fun mutedUntilNeverExceedsMaxForAnyPreset() {
        val now = 10_000_000L
        GroupMutePolicy.presets.forEach { preset ->
            assertTrue(GroupMutePolicy.mutedUntil(now, preset) <= now + GroupMutePolicy.MAX_MUTE_MS)
        }
        assertEquals(now + GroupMutePolicy.MAX_MUTE_MS, GroupMutePolicy.mutedUntil(now, Preset.DAYS_30))
    }

    @Test
    fun smallPresetDurationsAreExact() {
        val now = 2_000_000L
        assertEquals(now + 300_000L, GroupMutePolicy.mutedUntil(now, Preset.MINUTES_5))
        assertEquals(now + 1_800_000L, GroupMutePolicy.mutedUntil(now, Preset.MINUTES_30))
        assertEquals(now + 86_400_000L, GroupMutePolicy.mutedUntil(now, Preset.DAY_1))
    }

    @Test
    fun pastAndClearedMutesAreInactive() {
        val now = 5_000_000L
        assertFalse(GroupMutePolicy.isActiveMute(now - 1, now))
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), now))
    }

    @Test
    fun namedPresetDurationsAreExact() {
        assertEquals(600_000L, Preset.MINUTES_10.durationMs)
        assertEquals(7_200_000L, Preset.HOURS_2.durationMs)
        assertEquals(28_800_000L, Preset.HOURS_8.durationMs)
        assertEquals(604_800_000L, Preset.DAYS_7.durationMs)
    }

    @Test
    fun remainingPresetDurationsAreExact() {
        assertEquals(10_800_000L, Preset.HOURS_3.durationMs)
        assertEquals(21_600_000L, Preset.HOURS_6.durationMs)
        assertEquals(GroupMutePolicy.MAX_MUTE_MS, Preset.DAYS_30.durationMs)
    }

    @Test
    fun mutedUntilFromZeroEqualsEachDuration() {
        GroupMutePolicy.presets.forEach { preset ->
            assertEquals(preset.durationMs, GroupMutePolicy.mutedUntil(0L, preset))
        }
    }

    @Test
    fun maxMuteConstantIsThirtyDays() {
        assertEquals(30L * 24L * 60L * 60L * 1_000L, GroupMutePolicy.MAX_MUTE_MS)
        assertEquals(2_592_000_000L, GroupMutePolicy.MAX_MUTE_MS)
    }

    @Test
    fun clearedMuteIsInactiveAtNonNegativeNow() {
        assertEquals(0L, GroupMutePolicy.clearMuteUntil())
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), 0L))
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), 5_000_000L))
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), Long.MAX_VALUE))
    }

    @Test
    fun presetsListEqualsPresetEntries() {
        assertEquals(Preset.entries, GroupMutePolicy.presets)
    }

}
