package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentWriteErrorPolicyTest {

    @Test
    fun `required names a single field`() {
        assertEquals("Error: userId required", AgentWriteErrorPolicy.required("userId"))
    }

    @Test
    fun `required joins two fields with and`() {
        assertEquals(
            "Error: chatId and title required",
            AgentWriteErrorPolicy.required("chatId", "title"),
        )
    }

    @Test
    fun `required joins three fields with and`() {
        assertEquals(
            "Error: a and b and c required",
            AgentWriteErrorPolicy.required("a", "b", "c"),
        )
    }

    @Test
    fun `required joins messageId and starred`() {
        assertEquals(
            "Error: messageId and starred required",
            AgentWriteErrorPolicy.required("messageId", "starred"),
        )
    }

    @Test
    fun `required joins messageId and emoji`() {
        assertEquals(
            "Error: messageId and emoji required",
            AgentWriteErrorPolicy.required("messageId", "emoji"),
        )
    }

    @Test
    fun `required joins chatId and messageId`() {
        assertEquals(
            "Error: chatId and messageId required",
            AgentWriteErrorPolicy.required("chatId", "messageId"),
        )
    }

    @Test
    fun `notSignedIn matches the host error`() {
        assertEquals("Error: not signed in", AgentWriteErrorPolicy.notSignedIn())
    }

    @Test
    fun `chatNotFound matches the lookup error`() {
        assertEquals("Error: chat not found", AgentWriteErrorPolicy.chatNotFound())
    }

    @Test
    fun `taskNotFound matches the lookup error`() {
        assertEquals("Error: task not found", AgentWriteErrorPolicy.taskNotFound())
    }
}
