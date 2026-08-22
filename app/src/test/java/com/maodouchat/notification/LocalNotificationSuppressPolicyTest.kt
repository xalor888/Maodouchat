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

    @Test
    fun explicitDndOffNeverSuppressesWhenNotificationsOn() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23,
                dndEnabled = false,
                startMinute = 22 * 60,
                endMinute = 7 * 60,
                currentMinute = 23 * 60
            )
        )
    }

    @Test
    fun minutePrecisionInsideOvernightWindow() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 22,
                dndEnabled = true,
                startMinute = 22 * 60 + 30,
                endMinute = 7 * 60,
                currentMinute = 22 * 60 + 45
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 22,
                dndEnabled = true,
                startMinute = 22 * 60 + 30,
                endMinute = 7 * 60,
                currentMinute = 22 * 60 + 10
            )
        )
    }

    @Test
    fun runtimeFlagOffBypassesDndWindow() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23,
                dndRuntimeEnabled = false,
                dndEnabled = true
            )
        )
    }
}
