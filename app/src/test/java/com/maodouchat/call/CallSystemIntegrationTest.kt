package com.maodouchat.call

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

    @Test
    fun placeIncomingCallFalsePropagatesForFallback() {
        var placed: Triple<String, String, Boolean>? = null
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            placeTelecomIncoming = { name, id, video ->
                placed = Triple(name, id, video)
                false
            },
        )
        // Telecom 不可用时返回 false——调用方据此回退到应用内来电界面。
        assertFalse(integration.placeIncomingCall("Carol", "c7", isVideo = true))
        assertEquals(Triple("Carol", "c7", true), placed)
    }

    @Test
    fun lockScreenFlagsNeededRequeriesProvidersOnEachCall() {
        var active: String? = null
        var pending = false
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            activeCallId = { active },
            hasPendingIncomingCall = { pending },
        )
        assertFalse(integration.lockScreenFlagsNeeded())

        active = "c3"
        assertTrue(integration.lockScreenFlagsNeeded())

        active = null
        pending = true
        assertTrue(integration.lockScreenFlagsNeeded())
    }

    @Test
    fun blankActiveCallIdCountsAsNoActiveCall() {
        var pending = false
        val active: String? = "   "
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            activeCallId = { active },
            hasPendingIncomingCall = { pending },
        )
        // 全空白 id 视同无通话——锁屏旗标不开启。
        assertFalse(integration.lockScreenFlagsNeeded())
        pending = true
        assertTrue(integration.lockScreenFlagsNeeded())
    }

    @Test
    fun startCallForegroundForwardsEmptyNameAndVideoFalseVerbatim() {
        var started: Triple<String, Boolean, String>? = null
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            startForeground = { name, video, id -> started = Triple(name, video, id) },
        )
        integration.startCallForeground("", isVideo = false, callId = "c2")
        assertEquals(Triple("", false, "c2"), started)
    }

    @Test
    fun stopCallForegroundDelegatesEveryTime() {
        var stops = 0
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            stopForeground = { stops += 1 },
        )
        integration.stopCallForeground()
        integration.stopCallForeground()
        assertEquals(2, stops)
    }
}
