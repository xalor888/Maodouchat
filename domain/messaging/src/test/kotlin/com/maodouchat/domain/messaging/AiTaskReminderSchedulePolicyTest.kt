package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiTaskReminderSchedulePolicyTest {

    @Test
    fun `schedules when pending with a due time and reminders allowed`() {
        assertTrue(AiTaskReminderSchedulePolicy.shouldSchedule(false, null, 1_000L, true))
    }

    @Test
    fun `rejects each ineligible case`() {
        assertFalse(AiTaskReminderSchedulePolicy.shouldSchedule(true, null, 1_000L, true))
        assertFalse(AiTaskReminderSchedulePolicy.shouldSchedule(false, 500L, 1_000L, true))
        assertFalse(AiTaskReminderSchedulePolicy.shouldSchedule(false, null, null, true))
        assertFalse(AiTaskReminderSchedulePolicy.shouldSchedule(false, null, 1_000L, false))
    }
}
