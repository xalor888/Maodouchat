package com.maodouchat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppForegroundLifecycleControllerTest {

    @Test
    fun onHostCreatedMarksForegroundWithoutPresence() {
        var foreground = false
        var presence: Boolean? = null
        var cleared = false
        val controller = AppForegroundLifecycleController(
            setAppInForeground = { foreground = it },
            clearActiveChatSurface = { cleared = true },
            sendPresence = { presence = it },
        )

        controller.onHostCreated()

        assertTrue(foreground)
        assertEquals(null, presence)
        assertFalse(cleared)
    }

    @Test
    fun onHostStoppedClearsSurfaceAndSendsOfflinePresence() {
        var foreground = true
        var presence: Boolean? = null
        var cleared = false
        val controller = AppForegroundLifecycleController(
            setAppInForeground = { foreground = it },
            clearActiveChatSurface = { cleared = true },
            sendPresence = { presence = it },
        )

        controller.onHostStopped()

        assertTrue(cleared)
        assertFalse(foreground)
        assertEquals(false, presence)
    }

    @Test
    fun onHostStartedMarksForegroundAndOnlinePresence() {
        var foreground = false
        var presence: Boolean? = null
        val controller = AppForegroundLifecycleController(
            setAppInForeground = { foreground = it },
            clearActiveChatSurface = { error("should not clear") },
            sendPresence = { presence = it },
        )

        controller.onHostStarted()

        assertTrue(foreground)
        assertEquals(true, presence)
    }
}
