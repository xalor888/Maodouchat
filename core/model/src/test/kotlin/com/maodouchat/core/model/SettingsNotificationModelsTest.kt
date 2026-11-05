package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNotificationModelsTest {

    @Test
    fun `notification settings default to dnd 22 to 7 disabled`() {
        val state = NotificationSettingsUiState()
        assertFalse(state.isLoading)
        assertTrue(state.enableNotifications)
        assertFalse(state.dndEnabled)
        assertEquals(22, state.dndStartHour)
        assertEquals(7, state.dndEndHour)
        assertEquals(22 * 60, state.dndStartMinute)
        assertEquals(7 * 60, state.dndEndMinute)
    }
}
