package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTaskResultPolicyTest {

    @Test
    fun `create complete and delete results carry task id`() {
        assertEquals("Created task task_1", AgentTaskResultPolicy.created("task_1"))
        assertEquals("Task task_1 completed=true", AgentTaskResultPolicy.completed("task_1", true))
        assertEquals("Task task_1 completed=false", AgentTaskResultPolicy.completed("task_1", false))
        assertEquals("Deleted task task_1", AgentTaskResultPolicy.deleted("task_1"))
    }
}
