package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineMessageReplacePolicyTest {

    private fun message(id: String, content: String) =
        Message(id = id, chatId = "c1", senderId = "u1", content = content, timestamp = 1L)

    @Test
    fun `replaces matching message and keeps order and others`() {
        val messages = listOf(message("m1", "a"), message("m2", "b"), message("m3", "c"))
        val updated = message("m2", "B")
        val result = TimelineMessageReplacePolicy.replace(messages, "m2", updated)
        assertEquals(listOf(message("m1", "a"), updated, message("m3", "c")), result)
    }

    @Test
    fun `replaces first message`() {
        val messages = listOf(message("m1", "a"), message("m2", "b"))
        val result = TimelineMessageReplacePolicy.replace(messages, "m1", message("m1", "A"))
        assertEquals(listOf(message("m1", "A"), message("m2", "b")), result)
    }

    @Test
    fun `replaces last message`() {
        val messages = listOf(message("m1", "a"), message("m2", "b"))
        val result = TimelineMessageReplacePolicy.replace(messages, "m2", message("m2", "B"))
        assertEquals(listOf(message("m1", "a"), message("m2", "B")), result)
    }

    @Test
    fun `unknown id returns list unchanged`() {
        val messages = listOf(message("m1", "a"), message("m2", "b"))
        val result = TimelineMessageReplacePolicy.replace(messages, "m9", message("m9", "x"))
        assertEquals(messages, result)
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(
            emptyList(),
            TimelineMessageReplacePolicy.replace(emptyList(), "m1", message("m1", "x"))
        )
    }
}
