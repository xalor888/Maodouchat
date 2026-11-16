package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuietHoursPresetLabelKindTest {

    @Test
    fun `all four presets map one to one`() {
        assertEquals(QuietHoursPresetLabelKind.NIGHT, quietHoursPresetLabelKind(QuietHoursPreset.Night))
        assertEquals(QuietHoursPresetLabelKind.LUNCH, quietHoursPresetLabelKind(QuietHoursPreset.Lunch))
        assertEquals(QuietHoursPresetLabelKind.SLEEP, quietHoursPresetLabelKind(QuietHoursPreset.Sleep))
        assertEquals(QuietHoursPresetLabelKind.WORKDAY, quietHoursPresetLabelKind(QuietHoursPreset.Workday))
    }
}
