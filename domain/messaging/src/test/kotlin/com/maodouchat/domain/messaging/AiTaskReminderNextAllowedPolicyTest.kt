package com.maodouchat.domain.messaging

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiTaskReminderNextAllowedPolicyTest {

    // 用默认时区构造时刻：断言只依赖同一时区内的相对关系，不依赖 CI 时区。
    private fun at(dayOffset: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `dnd disabled returns null`() {
        assertNull(AiTaskReminderNextAllowedPolicy.nextAllowedTime(at(0, 22, 31), false, 1350, 420))
    }

    @Test
    fun `outside window returns null`() {
        assertNull(AiTaskReminderNextAllowedPolicy.nextAllowedTime(at(0, 10, 0), true, 1350, 420))
    }

    @Test
    fun `non whole hour inside overnight window returns next day window end`() {
        assertEquals(
            at(1, 7, 0),
            AiTaskReminderNextAllowedPolicy.nextAllowedTime(at(0, 22, 31), true, 1350, 420)
        )
    }

    @Test
    fun `late night inside overnight window returns next day window end`() {
        assertEquals(
            at(1, 7, 0),
            AiTaskReminderNextAllowedPolicy.nextAllowedTime(at(0, 23, 30), true, 1350, 420)
        )
    }

    @Test
    fun `inside day window returns same day window end`() {
        assertEquals(
            at(0, 13, 0),
            AiTaskReminderNextAllowedPolicy.nextAllowedTime(at(0, 12, 30), true, 720, 780)
        )
    }

    @Test
    fun `equal bounds mean no window`() {
        assertNull(AiTaskReminderNextAllowedPolicy.nextAllowedTime(at(0, 22, 30), true, 1350, 1350))
    }
}
