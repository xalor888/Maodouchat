package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentContactRowPolicyTest {

    @Test
    fun `contactRow joins id display name and status with tabs`() {
        assertEquals(
            "u1\tAlice (Al)\tOnline",
            AgentContactRowPolicy.contactRow("u1", "Alice (Al)", "Online"),
        )
    }

    @Test
    fun `personRow joins id name and status with tabs`() {
        assertEquals(
            "u2\tBob\tBusy",
            AgentContactRowPolicy.personRow("u2", "Bob", "Busy"),
        )
    }

    @Test
    fun `friendRequestRow joins ids names status and message`() {
        assertEquals(
            "r1\tPENDING\tfrom=u1/Alice\tto=u2/Bob\tHi!",
            AgentContactRowPolicy.friendRequestRow("r1", "PENDING", "u1", "Alice", "u2", "Bob", "Hi!"),
        )
    }

    @Test
    fun `blockedRow joins id and name with a tab`() {
        assertEquals("u3\tCarol", AgentContactRowPolicy.blockedRow("u3", "Carol"))
    }

    @Test
    fun `meDetail renders four lines when user exists`() {
        assertEquals(
            "userId=u1\nname=Alice\nnickname=Al\nstatus=Online",
            AgentContactRowPolicy.meDetail("u1", "Alice", "Al", "Online"),
        )
        assertEquals(
            "userId=u1\nname=Alice\nnickname=\nstatus=",
            AgentContactRowPolicy.meDetail("u1", "Alice", null, ""),
        )
    }

    @Test
    fun `meDetail renders only userId when user is missing`() {
        assertEquals("userId=u9", AgentContactRowPolicy.meDetail("u9", null, null, null))
    }
}
