package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentReadPreviewPolicyTest {

    @Test
    fun `post list content truncates at 120`() {
        assertEquals("x".repeat(120), AgentReadPreviewPolicy.postListContent("x".repeat(200)))
        assertEquals("hi", AgentReadPreviewPolicy.postListContent("hi"))
    }

    @Test
    fun `post detail content truncates at 1000`() {
        assertEquals("x".repeat(1_000), AgentReadPreviewPolicy.postDetailContent("x".repeat(1_500)))
        assertEquals("hi", AgentReadPreviewPolicy.postDetailContent("hi"))
    }

    @Test
    fun `comment content truncates at 160`() {
        assertEquals("x".repeat(160), AgentReadPreviewPolicy.commentContent("x".repeat(300)))
        assertEquals("nice", AgentReadPreviewPolicy.commentContent("nice"))
    }

    @Test
    fun `notification preview truncates at 80`() {
        assertEquals("x".repeat(80), AgentReadPreviewPolicy.notificationPreview("x".repeat(120)))
        assertEquals("", AgentReadPreviewPolicy.notificationPreview(""))
    }

    @Test
    fun `contact status truncates at 40`() {
        assertEquals("x".repeat(40), AgentReadPreviewPolicy.contactStatus("x".repeat(60)))
        assertEquals("busy", AgentReadPreviewPolicy.contactStatus("busy"))
    }

    @Test
    fun `friend request message truncates at 80`() {
        assertEquals("x".repeat(80), AgentReadPreviewPolicy.friendRequestMessage("x".repeat(100)))
        assertEquals("hello", AgentReadPreviewPolicy.friendRequestMessage("hello"))
    }

    @Test
    fun `chat last message list truncates at 80`() {
        assertEquals("x".repeat(80), AgentReadPreviewPolicy.chatLastMessageList("x".repeat(90)))
        assertEquals("last", AgentReadPreviewPolicy.chatLastMessageList("last"))
    }

    @Test
    fun `chat last message detail truncates at 160`() {
        assertEquals("x".repeat(160), AgentReadPreviewPolicy.chatLastMessageDetail("x".repeat(200)))
        assertEquals("last", AgentReadPreviewPolicy.chatLastMessageDetail("last"))
    }

    @Test
    fun `search hit text truncates at 160`() {
        assertEquals("x".repeat(160), AgentReadPreviewPolicy.searchHitText("x".repeat(200)))
        assertEquals("hit", AgentReadPreviewPolicy.searchHitText("hit"))
    }
}
