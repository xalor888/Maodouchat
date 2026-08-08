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
        val until = GroupMutePolicy.mutedUntil(now, GroupMutePolicy.Preset.HOUR_1)
        assertEquals(now + 3_600_000L, until)
    }

    @Test
    fun activeAndClear() {
        val now = 5_000_000L
        assertTrue(GroupMutePolicy.isActiveMute(now + 1, now))
        assertFalse(GroupMutePolicy.isActiveMute(now, now))
        assertEquals(0L, GroupMutePolicy.clearMuteUntil())
    }
}
