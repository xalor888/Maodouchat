package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMutePolicyTest {

    @Test
    fun `presets are ordered positive and capped at 30 days`() {
        assertEquals(11, GroupMutePolicy.presets.size)
        assertEquals(GroupMutePreset.entries, GroupMutePolicy.presets)
        var previous = 0L
        GroupMutePolicy.presets.forEach { preset ->
            assertTrue(preset.durationMs > previous)
            assertTrue(preset.durationMs <= GroupMutePolicy.MAX_MUTE_MS)
            previous = preset.durationMs
        }
        assertEquals(GroupMutePolicy.MAX_MUTE_MS, GroupMutePreset.DAYS_30.durationMs)
    }

    @Test
    fun `mutedUntil adds duration and never exceeds the cap`() {
        assertEquals(1_000_000L + 3_600_000L, GroupMutePolicy.mutedUntil(1_000_000L, GroupMutePreset.HOUR_1))
        assertEquals(
            1_000_000L + GroupMutePolicy.MAX_MUTE_MS,
            GroupMutePolicy.mutedUntil(1_000_000L, GroupMutePreset.DAYS_30),
        )
    }

    @Test
    fun `active mute is strict and clear returns zero`() {
        assertTrue(GroupMutePolicy.isActiveMute(5_000_001L, 5_000_000L))
        assertFalse(GroupMutePolicy.isActiveMute(5_000_000L, 5_000_000L))
        assertEquals(0L, GroupMutePolicy.clearMuteUntil())
        assertFalse(GroupMutePolicy.isActiveMute(GroupMutePolicy.clearMuteUntil(), 5_000_000L))
    }
}
