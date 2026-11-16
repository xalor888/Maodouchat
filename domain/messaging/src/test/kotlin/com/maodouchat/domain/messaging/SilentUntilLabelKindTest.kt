package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SilentUntilLabelKindTest {

    @Test
    fun `all three options map one to one`() {
        assertEquals(SilentUntilLabelKind.HOUR_1, silentUntilLabelKind(SilentUntilOption.Hour1))
        assertEquals(SilentUntilLabelKind.HOURS_8, silentUntilLabelKind(SilentUntilOption.Hours8))
        assertEquals(SilentUntilLabelKind.HOURS_24, silentUntilLabelKind(SilentUntilOption.Hours24))
    }
}
