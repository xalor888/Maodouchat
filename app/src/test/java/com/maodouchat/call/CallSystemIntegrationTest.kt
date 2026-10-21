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

    @Test
    fun stopWithoutStartStillDelegates() {
        // 取消语义：没 start 就 stop——无守卫，照样透传一次。
        var stops = 0
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            stopForeground = { stops += 1 },
        )
        integration.stopCallForeground()
        assertEquals(1, stops)
    }

    @Test
    fun callEndedClearsLockScreenFlags() {
        // 通话结束（activeCallId 置空）→ 锁屏旗标关闭。
        var active: String? = "c1"
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            activeCallId = { active },
            hasPendingIncomingCall = { false },
        )
        assertTrue(integration.lockScreenFlagsNeeded())
        active = null
        assertFalse(integration.lockScreenFlagsNeeded())
    }

    @Test
    fun repeatedStartForwardsLatestArgs() {
        // 重复：连续两次 start，参数逐次透传不合并。
        val starts = mutableListOf<Triple<String, Boolean, String>>()
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            startForeground = { name, video, id -> starts += Triple(name, video, id) },
        )
        integration.startCallForeground("A", isVideo = false, callId = "c1")
        integration.startCallForeground("B", isVideo = true, callId = "c2")
        assertEquals(
            listOf(Triple("A", false, "c1"), Triple("B", true, "c2")),
            starts,
        )
    }

    @Test
    fun activeCallSwitchKeepsFlagsEnabled() {
        // 切换：activeCallId 从 c1 切到 c2，旗标保持开启（只看"有没有通话"不看"是谁"）。
        var active: String? = "c1"
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            activeCallId = { active },
            hasPendingIncomingCall = { false },
        )
        assertTrue(integration.lockScreenFlagsNeeded())
        active = "c2"
        assertTrue(integration.lockScreenFlagsNeeded())
    }

    @Test
    fun placeIncomingCallRequeriesTelecomEachTime() {
        // Telecom 可用性逐次查询不缓存：true→false，返回值跟随。
        var available = true
        val integration = CallSystemIntegration(
            appContext = mockk(relaxed = true),
            placeTelecomIncoming = { _, _, _ -> available },
        )
        assertTrue(integration.placeIncomingCall("D", "c5", isVideo = false))
        available = false
        assertFalse(integration.placeIncomingCall("D", "c5", isVideo = false))
    }
}
