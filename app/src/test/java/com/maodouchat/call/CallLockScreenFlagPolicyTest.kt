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

    @Test
    fun tabAndNewlineIdDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "\t\n",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun paddedNonBlankIdKeepsEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "  call-1 ",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun nonBreakingSpaceIdCountsAsNonBlank() {
        // U+00A0 不被 isBlank 视为空白：这类 id 照样点亮锁屏标志。
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "\u00A0",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun pendingPathIsDeterministicAcrossRepeatedCalls() {
        repeat(3) {
            assertTrue(
                CallLockScreenFlagPolicy.shouldEnable(
                    activeCallId = null,
                    hasPendingIncomingCall = true,
                )
            )
            assertTrue(
                CallLockScreenFlagPolicy.shouldEnable(
                    activeCallId = "  ",
                    hasPendingIncomingCall = true,
                )
            )
        }
    }

    @Test
    fun singleSpaceIdDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = " ",
                hasPendingIncomingCall = false,
            )
        )
    }
}
