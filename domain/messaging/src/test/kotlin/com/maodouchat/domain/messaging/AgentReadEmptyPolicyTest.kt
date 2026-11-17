package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentReadEmptyPolicyTest {

    @Test
    fun `noMissedCalls matches the empty list message`() {
        assertEquals("No missed calls.", AgentReadEmptyPolicy.noMissedCalls())
    }

    @Test
    fun `noDrafts matches the empty drafts message`() {
        assertEquals("No drafts.", AgentReadEmptyPolicy.noDrafts())
    }

    @Test
    fun `noDraft matches the single draft missing message`() {
        assertEquals("No draft.", AgentReadEmptyPolicy.noDraft())
    }

    @Test
    fun `noTasks matches the empty tasks message`() {
        assertEquals("No tasks.", AgentReadEmptyPolicy.noTasks())
    }

    @Test
    fun `noNotifications matches the empty notifications message`() {
        assertEquals("No notifications.", AgentReadEmptyPolicy.noNotifications())
    }
}
