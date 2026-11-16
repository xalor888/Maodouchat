package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class DndWindowPolicyTest {

    @Test
    fun `window clamps minutes to the day`() {
        assertEquals(0 to 1439, DndWindowPolicy.clampWindow(0, 1439))
        assertEquals(0 to 1439, DndWindowPolicy.clampWindow(-5, 2000))
        assertEquals(60 to 120, DndWindowPolicy.clampWindow(60, 120))
    }

    @Test
    fun `hour clamps to 0 to 23`() {
        assertEquals(23, DndWindowPolicy.clampHourOfDay(24))
        assertEquals(0, DndWindowPolicy.clampHourOfDay(-1))
        assertEquals(12, DndWindowPolicy.clampHourOfDay(12))
    }

    @Test
    fun `minute clamps to 0 to 1439`() {
        assertEquals(1439, DndWindowPolicy.clampMinuteOfDay(1440))
        assertEquals(0, DndWindowPolicy.clampMinuteOfDay(-1))
        assertEquals(720, DndWindowPolicy.clampMinuteOfDay(720))
    }
}
