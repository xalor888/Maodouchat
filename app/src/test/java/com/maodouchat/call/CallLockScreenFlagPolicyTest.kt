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
    fun nonBreakingSpaceIdCountsAsBlank() {
        // U+00A0 被 Kotlin 的 isBlank 视为空白（Unicode 空格分隔符）：纯 NBSP 的 id 不点亮锁屏标志。
        assertFalse(
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

    @Test
    fun zeroWidthSpaceIdKeepsEnabled() {
        // U+200B 是格式字符而非空白：isBlank 不认它，id 非空即点亮。
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "\u200B",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun emojiIdKeepsEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "📞",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun carriageReturnOnlyIdDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "\r",
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun longIdKeepsEnabled() {
        assertTrue(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "c".repeat(500),
                hasPendingIncomingCall = false,
            )
        )
    }

    @Test
    fun verticalTabOnlyIdDisablesFlags() {
        assertFalse(
            CallLockScreenFlagPolicy.shouldEnable(
                activeCallId = "\u000B",
                hasPendingIncomingCall = false,
            )
        )
    }
}
