package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReminderPolicyTest {

    @Test
    fun `quick delay presets are strictly increasing and within window`() {
        val delays = MessageReminderPolicy.QUICK_DELAYS_MS
        assertTrue(delays.isNotEmpty())
        delays.zipWithNext().forEach { (a, b) ->
            assertTrue("delays must increase: $a !< $b", a < b)
        }
        delays.forEach { delay ->
            assertTrue(delay >= MessageReminderPolicy.MIN_DELAY_MS)
            assertTrue(delay <= MessageReminderPolicy.MAX_DELAY_MS)
        }
    }

    @Test
    fun `window covers one minute to thirty days`() {
        assertEquals(60_000L, MessageReminderPolicy.MIN_DELAY_MS)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.MAX_DELAY_MS)
    }

    @Test
    fun `first preset is one minute`() {
        assertEquals(60_000L, MessageReminderPolicy.QUICK_DELAYS_MS.first())
    }

    @Test
    fun `last preset is seven days`() {
        assertEquals(7L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.QUICK_DELAYS_MS.last())
    }

    @Test
    fun `preset list has fourteen entries with twelve hours at index nine`() {
        assertEquals(14, MessageReminderPolicy.QUICK_DELAYS_MS.size)
        assertEquals(12L * 60L * 60L * 1_000L, MessageReminderPolicy.QUICK_DELAYS_MS[9])
    }

    @Test
    fun `day presets are exact multiples of twenty four hours`() {
        val day = 24L * 60L * 60L * 1_000L
        assertEquals(listOf(2L * day, 3L * day, 7L * day), MessageReminderPolicy.QUICK_DELAYS_MS.takeLast(3))
    }

    @Test
    fun `presets are pairwise distinct`() {
        val delays = MessageReminderPolicy.QUICK_DELAYS_MS
        assertEquals(delays.size, delays.toSet().size)
    }

    @Test
    fun `one minute floor matches scheduled message policy`() {
        // 两个策略的下界同为 1 分钟：最小延迟的产品约定统一
        assertEquals(ScheduledMessagePolicy.MIN_DELAY_MS, MessageReminderPolicy.MIN_DELAY_MS)
    }

    @Test
    fun `thirty day ceiling is deliberately above the seven day last preset`() {
        // 上限 30 天而档位只给到 7 天：更长的延迟只能手工输入
        assertTrue(MessageReminderPolicy.MAX_DELAY_MS > MessageReminderPolicy.QUICK_DELAYS_MS.last())
        assertEquals(30L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.MAX_DELAY_MS)
    }

    @Test
    fun `minute presets are one five fifteen thirty`() {
        assertEquals(
            listOf(60_000L, 300_000L, 900_000L, 1_800_000L),
            MessageReminderPolicy.QUICK_DELAYS_MS.take(4)
        )
    }

    @Test
    fun `hour presets run one two three four six twelve twenty-four`() {
        val hour = 60L * 60L * 1_000L
        assertEquals(
            listOf(1L * hour, 2L * hour, 3L * hour, 4L * hour, 6L * hour, 12L * hour, 24L * hour),
            MessageReminderPolicy.QUICK_DELAYS_MS.slice(4..10)
        )
    }

    @Test
    fun `every preset is a whole number of minutes`() {
        MessageReminderPolicy.QUICK_DELAYS_MS.forEach { delay ->
            assertEquals(0L, delay % MessageReminderPolicy.MIN_DELAY_MS)
        }
    }

    @Test
    fun `no preset falls between twenty-four hours and two days`() {
        val hour = 60L * 60L * 1_000L
        assertEquals(24L * hour, MessageReminderPolicy.QUICK_DELAYS_MS[10])
        assertEquals(48L * hour, MessageReminderPolicy.QUICK_DELAYS_MS[11])
        assertTrue(MessageReminderPolicy.QUICK_DELAYS_MS.none { it == 36L * hour })
    }

    @Test
    fun `window bounds are exact multiples of one minute`() {
        assertEquals(0L, MessageReminderPolicy.MAX_DELAY_MS % MessageReminderPolicy.MIN_DELAY_MS)
        assertEquals(43_200L, MessageReminderPolicy.MAX_DELAY_MS / MessageReminderPolicy.MIN_DELAY_MS)
    }

    @Test
    fun `consecutive preset gaps are never below one minute`() {
        val delays = MessageReminderPolicy.QUICK_DELAYS_MS
        delays.zipWithNext().forEach { (a, b) ->
            assertTrue("gap $a -> $b below one minute", b - a >= MessageReminderPolicy.MIN_DELAY_MS)
        }
    }

    @Test
    fun `reminder ceiling is above the scheduled message ceiling`() {
        assertTrue(MessageReminderPolicy.MAX_DELAY_MS > ScheduledMessagePolicy.MAX_DELAY_MS)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.MAX_DELAY_MS)
        assertEquals(7L * 24L * 60L * 60L * 1_000L, ScheduledMessagePolicy.MAX_DELAY_MS)
    }

    @Test
    fun `hour presets skip five hours`() {
        val hour = 60L * 60L * 1_000L
        assertTrue(MessageReminderPolicy.QUICK_DELAYS_MS.contains(4L * hour))
        assertTrue(MessageReminderPolicy.QUICK_DELAYS_MS.contains(6L * hour))
        assertTrue(MessageReminderPolicy.QUICK_DELAYS_MS.none { it == 5L * hour })
    }

    @Test
    fun `first four presets sum to fifty one minutes`() {
        assertEquals(3_060_000L, MessageReminderPolicy.QUICK_DELAYS_MS.take(4).sum())
    }

    @Test
    fun `seven days is the only week scale preset`() {
        val week = 7L * 24L * 60L * 60L * 1_000L
        assertEquals(listOf(week), MessageReminderPolicy.QUICK_DELAYS_MS.filter { it >= week })
    }
}
