package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNotificationSuppressPolicyTest {

    @Test
    fun globalOffSuppresses() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = false,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 12
            )
        )
    }

    @Test
    fun equalBoundsMeansDndOff() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 22,
                hourOfDay = 22
            )
        )
    }

    @Test
    fun daytimeWindow() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 12
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 17
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 8
            )
        )
    }

    @Test
    fun overnightWindow() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 3
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 12
            )
        )
    }
}
