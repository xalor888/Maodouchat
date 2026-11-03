package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTaskReminderWorkNamingTest {

    @Test
    fun `task work name prefixes the task id`() {
        assertEquals("ai_task_reminder_42", AiTaskReminderWorkNaming.taskWorkName("42"))
    }

    @Test
    fun `periodic and reconcile work names differ`() {
        assertTrue(
            AiTaskReminderWorkNaming.UNIQUE_PERIODIC_WORK !=
                AiTaskReminderWorkNaming.UNIQUE_RECONCILE_WORK
        )
    }

    @Test
    fun `tags distinguish all tasks from one task`() {
        assertTrue(AiTaskReminderWorkNaming.TAG_ALL != AiTaskReminderWorkNaming.TAG_TASK)
    }
}
