package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatMessageCopyLabelTest {

    @Test
    fun `blank sender returns body as-is`() {
        assertEquals("hello", copyMessageLabelWithSender("", "hello"))
        assertEquals("hello", copyMessageLabelWithSender("   ", "hello"))
    }

    @Test
    fun `non-blank sender prefixes with colon`() {
        assertEquals("Alice: hello", copyMessageLabelWithSender("Alice", "hello"))
    }

    @Test
    fun `blank body returns time only`() {
        assertEquals("11-16 15:09", copyMessageLabelWithSenderTime("11-16 15:09", "Alice", ""))
        assertEquals("11-16 15:09", copyMessageLabelWithSenderTime("11-16 15:09", "", "  "))
    }

    @Test
    fun `blank sender returns time plus body`() {
        assertEquals("11-16 15:09 hello", copyMessageLabelWithSenderTime("11-16 15:09", "", "hello"))
    }

    @Test
    fun `sender and body returns time sender body`() {
        assertEquals(
            "11-16 15:09 Alice: hello",
            copyMessageLabelWithSenderTime("11-16 15:09", "Alice", "hello")
        )
    }
}
