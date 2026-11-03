package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSoundPolicyTest {

    @Test
    fun `message sound needs both flag and preference`() {
        assertTrue(NotificationSoundPolicy.messageSoundEnabled(true, true))
        assertFalse(NotificationSoundPolicy.messageSoundEnabled(false, true))
        assertFalse(NotificationSoundPolicy.messageSoundEnabled(true, false))
    }

    @Test
    fun `muted chat silences tray sound`() {
        assertFalse(NotificationSoundPolicy.trayMessageSoundEnabled(true, true, true))
        assertTrue(NotificationSoundPolicy.trayMessageSoundEnabled(true, true, false))
    }

    @Test
    fun `foreground app never plays in-app tone`() {
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = true,
                appInForeground = true
            )
        )
        assertTrue(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = true,
                appInForeground = false
            )
        )
    }

    @Test
    fun `tray sound only in background`() {
        assertFalse(NotificationSoundPolicy.traySoundAllowed(true, true))
        assertTrue(NotificationSoundPolicy.traySoundAllowed(false, true))
    }
}
