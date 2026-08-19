package com.maodouchat.call

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLockScreenFlagPolicyTest {

    @Test
    fun activeServiceKeepsFlagsEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "call-1",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun pendingIncomingCallKeepsFlagsEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "",
                hasPendingIncomingCall = true,
            )
        )
    }

    @Test
    fun staleIntentWithNoPendingCallDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun blankServiceIdAfterBackgroundHangupDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "   ",
                hasPendingIncomingCall = false,
            )
        )
    }
}
