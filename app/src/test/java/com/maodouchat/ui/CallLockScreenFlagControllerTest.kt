package com.maodouchat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLockScreenFlagControllerTest {

    @Test
    fun onHostStartedTickAppliesCurrentNeed() {
        var applied: Boolean? = null
        var needed = true
        val controller = CallLockScreenFlagController(
            flagsNeeded = { needed },
            applyFlags = { applied = it },
        )

        assertTrue(controller.onHostStartedTick())
        assertEquals(true, applied)

        needed = false
        assertTrue(controller.onHostStartedTick())
        assertEquals(false, applied)
    }

    @Test
    fun onHostStoppedReappliesNeedWithoutForcingOff() {
        var applied: Boolean? = null
        val controller = CallLockScreenFlagController(
            flagsNeeded = { true },
            applyFlags = { applied = it },
        )

        controller.onHostStopped()
        assertEquals(true, applied)
    }

    @Test
    fun onHostDestroyedAlwaysClearsFlags() {
        var applied: Boolean? = null
        val controller = CallLockScreenFlagController(
            flagsNeeded = { true },
            applyFlags = { applied = it },
        )

        controller.onHostDestroyed()
        assertEquals(false, applied)
        assertFalse(applied == true)
    }
}
