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

    @Test
    fun subCapGlobalFillLeavesPerChatWaitAtPerChatScale() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        repeat(238) { index -> AiRetryPolicy.recordCall("other-$index", AiRetryPolicyCategory.LIGHT) }
        // 全局 239/240 未满：单会话等待仍是自身 30 秒量级，新会话等待为 0。
        val wait = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT)
        assertTrue("own window should stay ~30s, was $wait", wait in 1L..30_000L)
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun sameChatTwoCategoriesConsumeTwoGlobalSlots() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        repeat(237) { index -> AiRetryPolicy.recordCall("other-$index", AiRetryPolicyCategory.LIGHT) }
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("other-237", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun freshChatConsumes240thSlotAndBlocksItself() {
        repeat(239) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("fresh", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("another-fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun heavy240thSlotReportsGlobalScaleWaitForRecordedChat() {
        repeat(239) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        AiRetryPolicy.recordCall("chat-239", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        // 第 240 槽由该 HEAVY 记录填满：它自己的剩余等待也是全局量级，而非 60 秒。
        val wait = AiRetryPolicy.remainingDelayMs("chat-239", AiRetryPolicyCategory.HEAVY)
        assertTrue("recorded HEAVY chat should report global-scale delay, was $wait", wait > 3_000_000L)
    }

    @Test
    fun secondHeavyRecordRefreshesPerChatWindow() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        val wait = AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY)
        assertTrue("re-recorded HEAVY window should be ~60s, was $wait", wait in 1L..60_000L)
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun categoryPrefixedChatIdDoesNotCollideWithCategoryKey() {
        AiRetryPolicy.recordCall("LIGHT:chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("LIGHT:chat-a", AiRetryPolicyCategory.LIGHT))
        // 窗口 key 是 "LIGHT:LIGHT:chat-a"，不是 "LIGHT:chat-a"：带类目前缀的会话是另一个键。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("LIGHT:chat-a", AiRetryPolicyCategory.HEAVY))
    }
    @Test
    fun tabChatIdIsDistinctFromSpaceAndEmptyKeys() {
        AiRetryPolicy.recordCall("\t", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("\t", AiRetryPolicyCategory.LIGHT))
        // 制表键与空格键、空串键互不相同：键不做空白归一。
        assertTrue(AiRetryPolicy.canCallNow(" ", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
    }
    @Test
    fun newlineChatIdIsDistinctFromSpaceKey() {
        AiRetryPolicy.recordCall("a\nb", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("a\nb", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("a b", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("a\tb", AiRetryPolicyCategory.LIGHT))
    }
    @Test
    fun repeatedProbesKeepBlockedChatBlocked() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        repeat(100) {
            assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        }
        // 只读探查既不清除窗口也不占槽：被拦会话仍被拦，其他会话不受影响。
        assertTrue(AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT) > 0L)
        assertTrue(AiRetryPolicy.canCallNow("chat-b", AiRetryPolicyCategory.LIGHT))
    }
    @Test
    fun unicodeNormalizationFormsAreDistinctKeys() {
        AiRetryPolicy.recordCall("\u00e9", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("\u00e9", AiRetryPolicyCategory.LIGHT))
        // 键按码点精确比较，不做 Unicode 归一：预组合 é 与 e + 组合重音是两个会话。
        assertTrue(AiRetryPolicy.canCallNow("e\u0301", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("e\u0301", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun emojiChatIdsAreDistinctKeys() {
        AiRetryPolicy.recordCall("\uD83D\uDE00", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("\uD83D\uDE00", AiRetryPolicyCategory.LIGHT))
        // 代理对按码元精确比较：另一个表情是另一个会话。
        assertTrue(AiRetryPolicy.canCallNow("\uD83D\uDE03", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("\uD83D\uDE03", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun emptyHeavyRecordKeepsLightFree() {
        AiRetryPolicy.recordCall("", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("", AiRetryPolicyCategory.LIGHT))
        val heavyWait = AiRetryPolicy.remainingDelayMs("", AiRetryPolicyCategory.HEAVY)
        assertTrue("HEAVY remaining should be ~60s, was $heavyWait", heavyWait in 1L..60_000L)
    }

    @Test
    fun categoryNameAsChatIdDoesNotCollide() {
        AiRetryPolicy.recordCall("HEAVY", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("HEAVY", AiRetryPolicyCategory.LIGHT))
        // 窗口 key 是 "LIGHT:HEAVY"，不是 "HEAVY:HEAVY"：同名类目会话是另一个键。
        assertTrue(AiRetryPolicy.canCallNow("HEAVY", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("HEAVY", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun newlineSuffixedChatIdIsDistinctKey() {
        AiRetryPolicy.recordCall("chat-a\n", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a\n", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun leadingColonChatIdIsDistinctKey() {
        AiRetryPolicy.recordCall(":chat", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow(":chat", AiRetryPolicyCategory.LIGHT))
        // 冒号只是键的一部分：前导冒号会话与无冒号会话、另一类目互不相同。
        assertTrue(AiRetryPolicy.canCallNow("chat", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow(":chat", AiRetryPolicyCategory.HEAVY))
    }


    @Test
    fun leadingSpaceChatIdIsDistinctFromPlainChatId() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        // 前导空格键是另一个键：窗口 key 不裁边，行首空格版不受影响。
        assertTrue(AiRetryPolicy.canCallNow(" chat-a", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall(" chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow(" chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun clearSessionFreesBothCategoriesAfterBothRecorded() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))

        AiRetryPolicy.clearSession()
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun heavyPrefixedChatIdDoesNotCollideWithCategoryKey() {
        AiRetryPolicy.recordCall("HEAVY:chat-a", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("HEAVY:chat-a", AiRetryPolicyCategory.HEAVY))
        // 窗口 key 是 "HEAVY:HEAVY:chat-a"，不是 "HEAVY:chat-a"：带类目前缀的会话是另一个键。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("HEAVY:chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun heavyOnlyFillReportsGlobalScaleDelayForFreshChats() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.HEAVY) }
        // 填充类目不影响全局等待量级：新会话两类都报全局约 60 分钟等待，不是类目窗口量级。
        val light = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        val heavy = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY)
        assertTrue("fresh LIGHT should report global-scale delay, was $light", light > 3_000_000L)
        assertTrue("fresh HEAVY should report global-scale delay, was $heavy", heavy > 3_000_000L)
    }

    @Test
    fun tabPrefixedChatIdIsDistinctFromPlainChatId() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        // 制表前缀键是另一个键：窗口 key 不做空白归一。
        assertTrue(AiRetryPolicy.canCallNow("\tchat-a", AiRetryPolicyCategory.LIGHT))

        AiRetryPolicy.recordCall("\tchat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("\tchat-a", AiRetryPolicyCategory.LIGHT))
    }


    @Test
    fun prefixChatIdIsDistinctFromExtendedChatId() {
        AiRetryPolicy.recordCall("chat-a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a-b", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a-b", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun trailingSpaceChatIdIsDistinctFromPlainChatId() {
        AiRetryPolicy.recordCall("chat-a ", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat-a ", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun carriageReturnChatIdIsDistinctFromPlainAndNewlineKeys() {
        AiRetryPolicy.recordCall("chat\ra", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\ra", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat\na", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun heavyChatIdKeysAreCaseSensitive() {
        AiRetryPolicy.recordCall("Chat-A", AiRetryPolicyCategory.HEAVY)
        assertFalse(AiRetryPolicy.canCallNow("Chat-A", AiRetryPolicyCategory.HEAVY))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun awaitBackoffReturnsImmediatelyWhenNoDelayForHeavy() = runBlocking {
        AiRetryPolicy.awaitBackoff("fresh", AiRetryPolicyCategory.HEAVY)
        assertTrue(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY))
    }

    @Test
    fun verticalTabChatIdIsDistinctFromPlainAndTabKeys() {
        AiRetryPolicy.recordCall("chat\u000Ba", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u000Ba", AiRetryPolicyCategory.LIGHT))
        // 纵向制表键与普通键、横向制表键互不相同：键不做控制字符归一。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat\ta", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun formFeedChatIdIsDistinctFromPlainAndSpaceKeys() {
        AiRetryPolicy.recordCall("chat\u000Ca", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u000Ca", AiRetryPolicyCategory.LIGHT))
        // 换页键与普通键、空格键互不相同：键不做空白归一。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun nullCharChatIdIsDistinctKey() {
        AiRetryPolicy.recordCall("chat\u0000a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u0000a", AiRetryPolicyCategory.LIGHT))
        // 空字符只是键的一部分：含 NUL 的会话与普通会话互不相同。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun extraRecordsUnderGlobalBlockKeepOldestTimestamp() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        val waitBefore = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        assertTrue("global-blocked fresh chat should report a positive delay, was $waitBefore", waitBefore > 3_000_000L)
        // 满槽后继续记录只追加队尾，不动队首最老时间戳：全局等待只随时间流逝缩短。
        repeat(10) { index -> AiRetryPolicy.recordCall("late-$index", AiRetryPolicyCategory.LIGHT) }
        val waitAfter = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        assertTrue("extra records must not refresh the global window, before=$waitBefore after=$waitAfter", waitAfter <= waitBefore)
        assertTrue("global window should still dominate, was $waitAfter", waitAfter > 3_000_000L)
        assertFalse(AiRetryPolicy.canCallNow("another-fresh", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun globalBlockWaitIsSameForRecordedAndFreshChats() {
        repeat(240) { index -> AiRetryPolicy.recordCall("chat-$index", AiRetryPolicyCategory.LIGHT) }
        val freshWait = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        val recordedWait = AiRetryPolicy.remainingDelayMs("chat-0", AiRetryPolicyCategory.LIGHT)
        // 满槽后两类等待都取全局量级：已记会话的 30 秒单窗被全局 60 分钟压过，与新会话同值。
        assertTrue("fresh chat should report global-scale delay, was $freshWait", freshWait > 3_000_000L)
        assertTrue("recorded chat should report global-scale delay, was $recordedWait", recordedWait > 3_000_000L)
        assertTrue("same global window, fresh=$freshWait recorded=$recordedWait", recordedWait <= freshWait)
        assertTrue("same global window, fresh=$freshWait recorded=$recordedWait", freshWait - recordedWait < 60_000L)
    }


    @Test
    fun zeroWidthSpaceChatIdIsDistinctFromPlainAndEmptyKeys() {
        val zwsp = "\u200Bchat"
        AiRetryPolicy.recordCall(zwsp, AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow(zwsp, AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun embeddedNewlineChatIdIsDistinctFromItsParts() {
        AiRetryPolicy.recordCall("a\nb", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("a\nb", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("b", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun trailingColonChatIdIsDistinctFromPlainChatId() {
        AiRetryPolicy.recordCall("chat:", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat:", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun categoryNameColonChatIdDoesNotCollide() {
        AiRetryPolicy.recordCall("LIGHT:", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("LIGHT:", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("LIGHT", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun backslashChatIdIsDistinctFromSlashChatId() {
        AiRetryPolicy.recordCall("a\\b", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("a\\b", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("a/b", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("ab", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun plusSignChatIdIsDistinctFromSpaceChatId() {
        AiRetryPolicy.recordCall("a+b", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("a+b", AiRetryPolicyCategory.LIGHT))
        // 键是原始字符串，不做 URL 解码：加号键与空格键互不相同。
        assertTrue(AiRetryPolicy.canCallNow("a b", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("ab", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun secondRecordDoesNotAccumulatePerChatWait() {
        // 单会话窗口只存一条时间戳：重复记录刷新起点，不把等待叠加成 60 秒。
        AiRetryPolicy.recordCall("chat-acc", AiRetryPolicyCategory.LIGHT)
        AiRetryPolicy.recordCall("chat-acc", AiRetryPolicyCategory.LIGHT)
        val wait = AiRetryPolicy.remainingDelayMs("chat-acc", AiRetryPolicyCategory.LIGHT)
        assertTrue("second record should refresh, not accumulate, was $wait", wait in 1L..30_000L)
        assertFalse(AiRetryPolicy.canCallNow("chat-acc", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun awaitBackoffReturnsImmediatelyWhenNoDelayForLight() = runBlocking {
        // 无等待时 awaitBackoff 不挂起：新会话直接返回。
        AiRetryPolicy.awaitBackoff("fresh-light", AiRetryPolicyCategory.LIGHT)
        assertTrue(AiRetryPolicy.canCallNow("fresh-light", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun sameChatRepeatedRecordsCountTowardGlobalWindow() {
        // 全局窗口按调用次数计，不按互异会话计：同一会话记满 240 次同样封顶。
        repeat(240) { AiRetryPolicy.recordCall("chat-same", AiRetryPolicyCategory.LIGHT) }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        val wait = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.LIGHT)
        assertTrue("global window should dominate, was $wait", wait > 3_000_000L)
    }

    @Test
    fun mixedCategoriesFillGlobalWindowTogether() {
        // 全局窗口两类目共用一个队列：LIGHT 与 HEAVY 各 120 次同样记满 240。
        repeat(120) { index -> AiRetryPolicy.recordCall("light-$index", AiRetryPolicyCategory.LIGHT) }
        repeat(120) { index -> AiRetryPolicy.recordCall("heavy-$index", AiRetryPolicyCategory.HEAVY) }
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.LIGHT))
        assertFalse(AiRetryPolicy.canCallNow("fresh", AiRetryPolicyCategory.HEAVY))
        val wait = AiRetryPolicy.remainingDelayMs("fresh", AiRetryPolicyCategory.HEAVY)
        assertTrue("global window should dominate, was $wait", wait > 3_000_000L)
    }

    @Test
    fun delCharChatIdIsDistinctKey() {
        AiRetryPolicy.recordCall("chat\u007Fa", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u007Fa", AiRetryPolicyCategory.LIGHT))
        // DEL 只是键的一部分：含 DEL 的会话与普通会话互不相同。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun sohCharChatIdIsDistinctKey() {
        AiRetryPolicy.recordCall("chat\u0001a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u0001a", AiRetryPolicyCategory.LIGHT))
        // SOH 控制字符不做归一：含 SOH 的会话与普通会话互不相同。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun nelCharChatIdIsDistinctFromPlainAndNewlineKeys() {
        AiRetryPolicy.recordCall("chat\u0085a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u0085a", AiRetryPolicyCategory.LIGHT))
        // NEL 与换行是不同字符：含 NEL 的键既不同于普通键，也不同于换行键。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat\na", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun lrmCharChatIdIsDistinctKey() {
        AiRetryPolicy.recordCall("chat\u200Ea", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u200Ea", AiRetryPolicyCategory.LIGHT))
        // LRM 是零宽方向控制符：含 LRM 的会话与普通会话互不相同。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun unitSeparatorChatIdIsDistinctFromPlainAndSpaceKeys() {
        AiRetryPolicy.recordCall("chat\u001Fa", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u001Fa", AiRetryPolicyCategory.LIGHT))
        // 单元分隔符与空格是不同字符：含 US 的键既不同于普通键，也不同于空格键。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun rlmCharChatIdIsDistinctFromPlainAndLrmKeys() {
        AiRetryPolicy.recordCall("chat\u200Fa", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u200Fa", AiRetryPolicyCategory.LIGHT))
        // RLM 与 LRM 是不同方向控制符：含 RLM 的键既不同于普通键，也不同于 LRM 键。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat\u200Ea", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun bomCharChatIdIsDistinctFromPlainKey() {
        AiRetryPolicy.recordCall("\uFEFFchat", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("\uFEFFchat", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun softHyphenChatIdIsDistinctFromPlainAndJoinedKeys() {
        AiRetryPolicy.recordCall("chat\u00ADa", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u00ADa", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chata", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun wordJoinerChatIdIsDistinctFromPlainAndJoinedKeys() {
        AiRetryPolicy.recordCall("chat\u2060a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u2060a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chata", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun c1ControlChatIdIsDistinctFromPlainAndNelKeys() {
        AiRetryPolicy.recordCall("chat\u0084a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u0084a", AiRetryPolicyCategory.LIGHT))
        // C1 控制符与 NEL 是不同字符：含 C1 的键既不同于普通键，也不同于 NEL 键。
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat\u0085a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }


    @Test
    fun nbspChatIdIsDistinctFromSpaceAndJoinedKeys() {
        AiRetryPolicy.recordCall("chat\u00A0a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u00A0a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chata", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun ideographicSpaceChatIdIsDistinctFromSpaceKey() {
        AiRetryPolicy.recordCall("chat\u3000a", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u3000a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chata", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun bidiEmbeddingChatIdsAreDistinctFromPlainAndEachOther() {
        AiRetryPolicy.recordCall("chat\u202Aa", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u202Aa", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat\u202Ba", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun bidiPopDirectionalChatIdIsDistinctFromPlainKey() {
        AiRetryPolicy.recordCall("chat\u202Ca", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\u202Ca", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chata", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chat-a", AiRetryPolicyCategory.LIGHT))
    }

    @Test
    fun variationSelectorChatIdIsDistinctFromPlainAndJoinedKeys() {
        AiRetryPolicy.recordCall("chat\uFE0Fa", AiRetryPolicyCategory.LIGHT)
        assertFalse(AiRetryPolicy.canCallNow("chat\uFE0Fa", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chata", AiRetryPolicyCategory.LIGHT))
        assertTrue(AiRetryPolicy.canCallNow("chat-a", AiRetryPolicyCategory.LIGHT))
        assertEquals(0L, AiRetryPolicy.remainingDelayMs("chata", AiRetryPolicyCategory.LIGHT))
    }

}
