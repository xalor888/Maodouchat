package com.maodouchat.ai

import kotlinx.coroutines.runBlocking
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

    @Test
    fun globalWindowBoundaryAt239And240() {
        repeat(239) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("chat-239", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun clearSessionAlsoDropsPerChatWindow() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun globalBlockSurfacesDelayOnUnseenChat() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        val delay = AiRetryPolicy.remainingDelayMs("never-seen", AiRetryPolicyCategory.HEAVY)
        assertTrue("global-blocked fresh chat should report a positive delay, was $delay", delay > 0L)
    }

    @Test
    fun lightAndHeavyRemainingDelaysStayIndependent() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        val light = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        val heavy = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY)
        assertTrue("LIGHT remaining should be ~30s, was $light", light in 1L..30_000L)
        assertTrue("HEAVY remaining should be ~60s, was $heavy", heavy in 1L..60_000L)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun chatIdIsMatchedExactlyWithoutTrimming() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        // "chat-a " 是另一个键：窗口 key 不裁边。
        assertTrue(AiRetryPolicy.canCallNow("chat-a ", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun emptySessionReportsZeroRemainingDelayForBothCategories() {
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun emptyChatIdIsItsOwnDistinctKey() {
        AiRetryPolicy.recordCall("", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun perChatWindowsAreIsolatedBetweenChats() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-b", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-b", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun everyRecordCallConsumesExactlyOneGlobalSlot() {
        // 同一会话同类目连续记两次：全局窗口是 240 槽，238 个异构会话 + 2 = 满。
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        repeat(237) { index -> AiRetryPolicy.recordCall("other-$index", AiRetryPolicyCategory.LIGHT) }
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("other-237", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun repeatedClearSessionKeepsWindowsUsable() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.clearSession()
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun globalDelayDominatesPerChatDelay() {
        // remainingDelayMs 取两者最大值：全局窗口约 60 分钟，压过单会话 30 秒。
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        repeat(239) { index -> AiRetryPolicy.recordCall("other-$index", AiRetryPolicyCategory.LIGHT) }
        val wait = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        assertTrue("global window should dominate per-chat delay, was $wait", wait > 3_000_000L)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun freshChatUnderGlobalBlockReportsGlobalScaleDelayForBothCategories() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        val light = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        val heavy = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY)
        assertTrue("fresh LIGHT should report global-scale delay, was $light", light > 3_000_000L)
        assertTrue("fresh HEAVY should report global-scale delay, was $heavy", heavy > 3_000_000L)
    }

    @Test
    fun readOnlyProbesDoNotConsumeGlobalSlots() {
        // canCallNow / remainingDelayMs 只读不记：反复探查不占全局槽位。
        repeat(300) {
            AiRetryPolicy.canCallNow("probe", AiRetryPolicyCategory.LIGHT)
            AiRetryPolicy.remainingDelayMs("probe", AiRetryPolicyCategory.LIGHT)
        }
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun emptyChatIdKeepsCategoriesIsolated() {
        AiRetryPolicy.recordCall("", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun subCapTrafficFromOtherChatsLeavesOwnWindowIntact() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        repeat(100) { index -> AiRetryPolicy.recordCall("other-$index", AiRetryPolicyCategory.LIGHT) }
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        val wait = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        assertTrue("own window should stay ~30s, was $wait", wait in 1L..30_000L)
    }

    @Test
    fun chatIdKeysAreCaseSensitive() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        // 窗口 key 不做大小写归一："Chat-A" 是另一个会话。
        assertTrue(AiRetryPolicy.canCallNow("Chat-A", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("Chat-A", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun mixedCategoryBoundaryAt240() {
        repeat(239) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        // 第 240 槽由 HEAVY 填满：全局上限跨类目生效，两类新会话都被挡。
        AiRetryPolicy.recordCall("chat-239", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun clearSessionRestoresZeroDelayAfterGlobalBlock() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        assertTrue(AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT) > 0L)

        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun awaitBackoffReturnsImmediatelyWhenNoDelay() = runBlocking {
        // 无剩余等待时 awaitBackoff 不延迟：直接返回且不占窗口。
        AiRetryPolicy.awaitBackoff("fresh", AiRetryPolicyCategory.LIGHT)
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun blankChatIdIsDistinctFromEmpty() {
        AiRetryPolicy.recordCall(" ", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow(" ", AiRetryPolicyCategory.LIGHT))
        // 空白键与空串键不同：空串会话不受影响。
        assertTrue(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow(" ", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun repeatedSameChatRecordsFillTheGlobalWindow() {
        // 全局窗口按记录次数计槽，不按会话去重：同一会话记满 240 次同样封顶。
        repeat(240) { AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT) }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        val wait = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        assertTrue("global window should dominate, was $wait", wait > 3_000_000L)
    }

    @Test
    fun heavyWindowsAreIsolatedBetweenChats() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-b", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-b", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun clearSessionMidFillRestoresFullGlobalCapacity() {
        repeat(239) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun whitespaceChatIdsOfDifferentLengthsAreDistinctKeys() {
        AiRetryPolicy.recordCall(" ", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow(" ", AiRetryPolicyCategory.LIGHT))
        // 键不裁边也不归一：两个空格是另一个会话。
        assertTrue(AiRetryPolicy.canCallNow("  ", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("  ", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("  ", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("  ", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow(" ", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun recordsUnderAGlobalBlockKeepItBlocked() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        AiRetryPolicy.recordCall("late", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("another-fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("another-fresh", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.remainingDelayMs("another-fresh", AiRetryPolicyCategory.LIGHT) > 0L)
    }

    @Test
    fun colonInsideChatIdDoesNotCollideWithCategoryPrefix() {
        AiRetryPolicy.recordCall("a:b", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("a:b", AiRetryPolicyCategory.LIGHT))
        // 窗口 key 是 "LIGHT:a:b"，不是 "LIGHT:a"：冒号只是键的一部分。
        assertTrue(AiRetryPolicy.canCallNow("a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("a:b", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun heavyRemainingExceedsLightRemainingWhenBothRecordedTogether() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        val light = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        val heavy = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY)
        assertTrue("LIGHT remaining should be ~30s, was $light", light in 1L..30_000L)
        assertTrue("HEAVY remaining should be ~60s, was $heavy", heavy in 1L..60_000L)
        assertTrue("HEAVY window should outlast LIGHT, light=$light heavy=$heavy", heavy > light)
    }

    @Test
    fun nonAsciiChatIdsAreDistinctKeys() {
        AiRetryPolicy.recordCall("聊天-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("聊天-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("聊天-b", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("聊天-b", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun veryLongChatIdWorksAsAKey() {
        val longId = "x".repeat(10_000)
        AiRetryPolicy.recordCall(longId, AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow(longId, AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun globalWindowRefillsToFullAfterClearSession() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.clearSession()
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun heavyOnlyGlobalFillBlocksBothCategories() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.HEAVY) }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT) > 0L)
        assertTrue(AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY) > 0L)
    }

    @Test
    fun secondLightRecordRefreshesPerChatWindow() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        val wait = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        assertTrue("re-recorded LIGHT window should be ~30s, was $wait", wait in 1L..30_000L)
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun partialFillClearedRestoresFreshAccess() {
        repeat(100) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun globalBlockDelayNeverExceedsOneHour() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        val wait = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        assertTrue("global window is 60 minutes, was $wait", wait in 1L..3_600_000L)
    }

    @Test
    fun blankAndEmptyKeysLeaveNamedChatUntouched() {
        AiRetryPolicy.recordCall("", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall(" ", AiRetryPolicyCategory.LIGHT)
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow(" ", AiRetryPolicyCategory.LIGHT))
    }

}
