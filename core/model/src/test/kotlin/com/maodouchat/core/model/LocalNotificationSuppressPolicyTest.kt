package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationSuppressPolicyTest {

    @Test
    fun `global off always suppresses`() {
        assertTrue(LocalNotificationSuppressPolicy.shouldSuppress(false, 22, 7, 12))
    }

    @Test
    fun `overnight window suppresses inside and not outside`() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22, dndEndHour = 7, hourOfDay = 23,
                startMinute = 1350, endMinute = 420, currentMinute = 1380
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22, dndEndHour = 7, hourOfDay = 12,
                startMinute = 1350, endMinute = 420, currentMinute = 720
            )
        )
    }

    @Test
    fun `equal bounds mean disabled`() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 8, dndEndHour = 8, hourOfDay = 8,
                startMinute = 480, endMinute = 480, currentMinute = 480
            )
        )
    }
}
