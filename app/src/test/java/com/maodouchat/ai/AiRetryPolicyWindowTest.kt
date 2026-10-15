package com.maodouchat.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * XAL-41：AiRetryPolicy 窗口隔离与全局上限。
 * 对象是进程级状态，每个用例前后 clearSession，避免与 AiRetryPolicyTest 串台。
 * 不追加 AiRetryPolicyTest。
 */
class AiRetryPolicyWindowTest {

    @Before
    fun resetBefore() {
        AiRetryPolicy.clearSession()
    }

    @After
    fun resetAfter() {
        AiRetryPolicy.clearSession()
    }

    @Test
    fun lightAndHeavyWindowsAreIsolatedOnTheSameChat() {
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))

        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)

        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-b", AiRetryPolicyCategory.LIGHT))

        val lightDelay = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        val heavyDelay = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY)
        assertTrue("LIGHT remaining should be ~30s, was $lightDelay", lightDelay in 1L..30_000L)
        assertEquals(0L, heavyDelay)
    }

    @Test
    fun heavyRecordDoesNotBlockLightOnSameChat() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        val heavyDelay = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY)
        assertTrue("HEAVY remaining should be ~60s, was $heavyDelay", heavyDelay in 1L..60_000L)
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun globalWindowCapsAt240AcrossChatsAndCategories() {
        repeat(240) { index ->
            val category = if (index % 2 == 0) AiRetryPolicyCategory.LIGHT else AiRetryPolicyCategory.HEAVY
            AiRetryPolicy.recordCall("chat-$index", category)
        }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))

        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
    }
}
