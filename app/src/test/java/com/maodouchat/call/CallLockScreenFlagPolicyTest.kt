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

    @Test
    fun nullCallIdWithoutPendingDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = null,
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun nullCallIdWithPendingKeepsEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = null,
                hasPendingIncomingCall = true,
            )
        )
    }

    @Test
    fun activeCallAndPendingTogetherKeepEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "call-1",
                hasPendingIncomingCall = true,
            )
        )
    }

    @Test
    fun blankCallIdWithPendingKeepsEnabled() {
        // id 为空但有待接来电：pending 通道优先。
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "  ",
                hasPendingIncomingCall = true,
            )
        )
    }

    @Test
    fun decisionIsDeterministicAcrossRepeatedCalls() {
        repeat(3) {
            assertFalse(
                CallLockScreenFlagPolicy.shouldEnable(
                    activeCallId = "",
                    hasPendingIncomingCall = false,
                )
            )
            assertTrue(
                CallLockScreenFlagPolicy.shouldEnable(
                    activeCallId = "call-9",
                    hasPendingIncomingCall = false,
                )
            )
        }
    }
}
