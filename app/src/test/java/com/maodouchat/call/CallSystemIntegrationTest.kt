package com.maodouchat.call

import android.content.Context
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSystemIntegrationTest {

    @Test
    fun startCallForegroundForwardsArgs() {
        var started: Triple<String, Boolean, String>? = null
        var stopped = false
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            startForeground = { name, video, id -> started = Triple(name, video, id) },
            stopForeground = { stopped = true },
        )

        integration.startCallForeground("Alice", isVideo = true, callId = "c1")
        assertEquals(Triple("Alice", true, "c1"), started)

        integration.stopCallForeground()
        assertTrue(stopped)
    }

    @Test
    fun lockScreenFlagsNeededUsesPolicyInputs() {
        var active: String? = "call-1"
        var pending = false
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            activeCallId = { active },
            hasPendingIncomingCall = { pending },
        )
        assertTrue(integration.lockScreenFlagsNeeded())

        active = null
        assertFalse(integration.lockScreenFlagsNeeded())

        pending = true
        assertTrue(integration.lockScreenFlagsNeeded())
    }

    @Test
    fun placeIncomingCallDelegatesToTelecom() {
        var placed: Triple<String, String, Boolean>? = null
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            placeTelecomIncoming = { name, id, video ->
                placed = Triple(name, id, video)
                true
            },
        )
        assertTrue(integration.placeIncomingCall("Bob", "c9", isVideo = false))
        assertEquals(Triple("Bob", "c9", false), placed)
    }

    @Test
    fun registerPhoneAccountDelegates() {
        var registered = false
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            registerTelecomAccount = { registered = true },
        )
        integration.registerPhoneAccount()
        assertTrue(registered)
    }
}
