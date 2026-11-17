package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTaskReadLimitPolicyTest {

    @Test
    fun `missedCallLimit clamps to 1 to 30`() {
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(-5))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(1))
        assertEquals(30, AgentTaskReadLimitPolicy.missedCallLimit(30))
        assertEquals(30, AgentTaskReadLimitPolicy.missedCallLimit(31))
    }

    @Test
    fun `taskQueryLimit clamps to 1 to 80`() {
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(1))
        assertEquals(80, AgentTaskReadLimitPolicy.taskQueryLimit(80))
        assertEquals(80, AgentTaskReadLimitPolicy.taskQueryLimit(81))
    }

    @Test
    fun `taskResultLimit clamps to 1 to 40`() {
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(1))
        assertEquals(40, AgentTaskReadLimitPolicy.taskResultLimit(40))
        assertEquals(40, AgentTaskReadLimitPolicy.taskResultLimit(41))
    }

    @Test
    fun `draft list holds at most 40 items`() {
        assertEquals(40, AgentTaskReadLimitPolicy.MAX_DRAFT_LIST_ITEMS)
    }

    @Test
    fun `draftText truncates at 160 without trimming`() {
        assertEquals("  hi", AgentTaskReadLimitPolicy.draftText("  hi"))
        assertEquals("x".repeat(160), AgentTaskReadLimitPolicy.draftText("x".repeat(200)))
        assertEquals("  " + "x".repeat(158), AgentTaskReadLimitPolicy.draftText("  " + "x".repeat(500)))
    }
}
