package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentReadEmptyLinePolicyTest {

    @Test
    fun `chat and message empty lines`() {
        assertEquals("No chats.", AgentReadEmptyLinePolicy.NO_CHATS)
        assertEquals("No messages.", AgentReadEmptyLinePolicy.NO_MESSAGES)
    }

    @Test
    fun `message search starred and pinned empty lines`() {
        assertEquals("No matches.", AgentReadEmptyLinePolicy.NO_MATCHES)
        assertEquals("No starred messages.", AgentReadEmptyLinePolicy.NO_STARRED_MESSAGES)
        assertEquals("No pinned messages.", AgentReadEmptyLinePolicy.NO_PINNED_MESSAGES)
    }

    @Test
    fun `contact empty lines`() {
        assertEquals("No contacts.", AgentReadEmptyLinePolicy.NO_CONTACTS)
        assertEquals("No friend requests.", AgentReadEmptyLinePolicy.NO_FRIEND_REQUESTS)
        assertEquals("No friends.", AgentReadEmptyLinePolicy.NO_FRIENDS)
        assertEquals("No users.", AgentReadEmptyLinePolicy.NO_USERS)
        assertEquals("No blocked users.", AgentReadEmptyLinePolicy.NO_BLOCKED_USERS)
    }

    @Test
    fun `post empty lines`() {
        assertEquals("No posts.", AgentReadEmptyLinePolicy.NO_POSTS)
        assertEquals("No comments.", AgentReadEmptyLinePolicy.NO_COMMENTS)
    }
}
