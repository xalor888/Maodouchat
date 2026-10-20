package com.maodouchat.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSessionGateTest {
    @Test
    fun stableSessionContinues() {
        assertTrue(BackgroundSessionGate.mayContinue("u1", "tok", "u1"))
    }

    @Test
    fun logoutOrSwitchAborts() {
        assertFalse(BackgroundSessionGate.mayContinue("u1", null, "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "", "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", null))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "u2"))
        assertFalse(BackgroundSessionGate.mayContinue("", "tok", "u1"))
    }

    @Test
    fun rejectsBlankExpectedEvenIfLiveMatches() {
        assertFalse(BackgroundSessionGate.mayContinue("  ", "tok", "  "))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", " u1"))
    }

    @Test
    fun whitespaceOnlyLiveValuesAbort() {
        // isNullOrBlank：纯空白令牌/用户 id 视同登出
        assertFalse(BackgroundSessionGate.mayContinue("u1", "   ", "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "\t", "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "   "))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "\n"))
    }

    @Test
    fun expectedUserIdWhitespaceVariantsAbort() {
        assertFalse(BackgroundSessionGate.mayContinue("\t", "tok", "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("\n", "tok", "\n"))
        // 期望 id 为空白时直接拒绝，不看实时值
        assertFalse(BackgroundSessionGate.mayContinue(" ", null, null))
    }

    @Test
    fun userIdComparisonIsExactAndCaseSensitive() {
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "U1"))
        assertFalse(BackgroundSessionGate.mayContinue("U1", "tok", "u1"))
        // 实时 id 不做 trim：尾空格也不算同一个账号
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "u1 "))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", " u1"))
    }

    @Test
    fun tokenValueIsNeverComparedOnlyItsPresence() {
        // 门禁只认账号一致，令牌换什么值都不影响放行
        assertTrue(BackgroundSessionGate.mayContinue("u1", "tok-a", "u1"))
        assertTrue(BackgroundSessionGate.mayContinue("u1", "completely-different-token", "u1"))
        assertTrue(BackgroundSessionGate.mayContinue("u1", "  padded  ", "u1"))
    }

    @Test
    fun gateDecisionIsDeterministicAcrossCalls() {
        repeat(3) {
            assertTrue(BackgroundSessionGate.mayContinue("u1", "tok", "u1"))
            assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "u2"))
            assertFalse(BackgroundSessionGate.mayContinue("u1", null, "u1"))
        }
    }

    @Test
    fun acceptsStableSessionAcrossCalls() {
        val owner = "owner-42"
        assertTrue(BackgroundSessionGate.mayContinue(owner, "access-a", owner))
        assertTrue(BackgroundSessionGate.mayContinue(owner, "access-b", owner))
        assertFalse(BackgroundSessionGate.mayContinue(owner, "access-b", "other"))
    }

}
