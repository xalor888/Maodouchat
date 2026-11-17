package com.maodouchat.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupMutePresetLabelKindTest {

    @Test
    fun `all eleven presets map to their kinds`() {
        assertEquals(GroupMutePresetLabelKind.MINUTES_5, groupMutePresetLabelKind(GroupMutePreset.MINUTES_5))
        assertEquals(GroupMutePresetLabelKind.MINUTES_10, groupMutePresetLabelKind(GroupMutePreset.MINUTES_10))
        assertEquals(GroupMutePresetLabelKind.MINUTES_30, groupMutePresetLabelKind(GroupMutePreset.MINUTES_30))
        assertEquals(GroupMutePresetLabelKind.HOUR_1, groupMutePresetLabelKind(GroupMutePreset.HOUR_1))
        assertEquals(GroupMutePresetLabelKind.HOURS_2, groupMutePresetLabelKind(GroupMutePreset.HOURS_2))
        assertEquals(GroupMutePresetLabelKind.HOURS_3, groupMutePresetLabelKind(GroupMutePreset.HOURS_3))
        assertEquals(GroupMutePresetLabelKind.HOURS_6, groupMutePresetLabelKind(GroupMutePreset.HOURS_6))
        assertEquals(GroupMutePresetLabelKind.HOURS_8, groupMutePresetLabelKind(GroupMutePreset.HOURS_8))
        assertEquals(GroupMutePresetLabelKind.DAY_1, groupMutePresetLabelKind(GroupMutePreset.DAY_1))
        assertEquals(GroupMutePresetLabelKind.DAYS_7, groupMutePresetLabelKind(GroupMutePreset.DAYS_7))
        assertEquals(GroupMutePresetLabelKind.DAYS_30, groupMutePresetLabelKind(GroupMutePreset.DAYS_30))
    }
}
