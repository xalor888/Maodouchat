package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentPostReadLimitPolicyTest {

    @Test
    fun `postsLimit clamps to 1 to 40`() {
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(0))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(1))
        assertEquals(40, AgentPostReadLimitPolicy.postsLimit(40))
        assertEquals(40, AgentPostReadLimitPolicy.postsLimit(41))
    }

    @Test
    fun `commentsLimit clamps to 1 to 100`() {
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(0))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(1))
        assertEquals(100, AgentPostReadLimitPolicy.commentsLimit(100))
        assertEquals(100, AgentPostReadLimitPolicy.commentsLimit(101))
    }
}
