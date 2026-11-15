package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageEditWindowPolicyTest {

    @Test
    fun justSentIsWithinWindow() {
        assertTrue(MessageEditWindowPolicy.withinWindow(nowMs = 1_000_000L, sentAtMs = 1_000_000L))
    }

    @Test
    fun justInsideWindowIsWithinWindow() {
        assertTrue(MessageEditWindowPolicy.withinWindow(nowMs = 1_299_999L, sentAtMs = 1_000_000L))
    }

    @Test
    fun exactBoundaryIsOutsideWindow() {
        assertFalse(MessageEditWindowPolicy.withinWindow(nowMs = 1_300_000L, sentAtMs = 1_000_000L))
    }

    @Test
    fun pastWindowIsOutsideWindow() {
        assertFalse(MessageEditWindowPolicy.withinWindow(nowMs = 1_600_000L, sentAtMs = 1_000_000L))
    }

    @Test
    fun futureSentAtIsWithinWindow() {
        // 时钟回拨/发送时间超前不做钳制：负耗时照公式算，仍在窗口内。
        assertTrue(MessageEditWindowPolicy.withinWindow(nowMs = 1_000_000L, sentAtMs = 1_060_000L))
    }

    @Test
    fun thirtySecondsAgoShowsFiveMinutes() {
        // 与 ChatDetailDialogsUiTest 的撤回倒计时断言一致：30 秒前显示 5 分钟。
        assertEquals(5, MessageEditWindowPolicy.remainingMinutes(nowMs = 1_000_000L, sentAtMs = 970_000L))
    }

    @Test
    fun fourMinutesAgoShowsTwoMinutes() {
        // 与 ChatDetailDialogsUiTest 的撤回倒计时断言一致：4 分钟前显示 2 分钟。
        assertEquals(2, MessageEditWindowPolicy.remainingMinutes(nowMs = 1_000_000L, sentAtMs = 760_000L))
    }

    @Test
    fun justInsideWindowShowsOneMinute() {
        assertEquals(1, MessageEditWindowPolicy.remainingMinutes(nowMs = 1_299_999L, sentAtMs = 1_000_000L))
    }

    @Test
    fun zeroElapsedShowsSixMinutes() {
        // 沿用 app 侧原公式：耗时为 0 时 300_000/60_000+1=6，UI 侧从不以耗时 0 展示。
        assertEquals(6, MessageEditWindowPolicy.remainingMinutes(nowMs = 1_000_000L, sentAtMs = 1_000_000L))
    }

    @Test
    fun futureSentAtShowsMoreThanFiveMinutes() {
        // 时钟回拨不做钳制：发送时间超前 1 分钟，剩余按公式原样算。
        assertEquals(7, MessageEditWindowPolicy.remainingMinutes(nowMs = 1_000_000L, sentAtMs = 1_060_000L))
    }
}
