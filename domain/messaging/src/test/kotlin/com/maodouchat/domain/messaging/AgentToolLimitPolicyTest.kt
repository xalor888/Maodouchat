package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentToolLimitPolicyTest {

    @Test
    fun `twelve for message search and missed calls`() {
        assertEquals(12, AgentToolLimitPolicy.defaultLimit("search_messages"))
        assertEquals(12, AgentToolLimitPolicy.defaultLimit("list_missed_calls"))
    }

    @Test
    fun `thirty for post comments`() {
        assertEquals(30, AgentToolLimitPolicy.defaultLimit("list_post_comments"))
    }

    @Test
    fun `twenty for the rest of the limit-taking tools`() {
        listOf(
            "get_chat_history",
            "list_starred_messages",
            "search_users",
            "list_posts",
            "list_local_tasks",
            "list_notifications"
        ).forEach { assertEquals(20, AgentToolLimitPolicy.defaultLimit(it)) }
    }

    @Test
    fun `unknown tool falls back to twenty`() {
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_chats"))
    }
}
