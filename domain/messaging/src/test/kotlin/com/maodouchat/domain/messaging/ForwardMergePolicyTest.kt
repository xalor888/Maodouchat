package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForwardMergePolicyTest {

    private fun message(id: String, senderId: String = "u1", content: String = "hi", type: MessageType = MessageType.TEXT) =
        Message(id = id, chatId = "c1", senderId = senderId, content = content, type = type, timestamp = 1L)

    @Test
    fun `single message is not mergeable`() {
        assertFalse(ForwardMergePolicy.isMergeable(listOf(message("m1"))))
    }

    @Test
    fun `message with image is not mergeable`() {
        assertFalse(
            ForwardMergePolicy.isMergeable(listOf(message("m1"), message("m2", type = MessageType.IMAGE)))
        )
    }

    @Test
    fun `text and markdown messages are mergeable`() {
        assertTrue(
            ForwardMergePolicy.isMergeable(
                listOf(message("m1"), message("m2", type = MessageType.MARKDOWN))
            )
        )
    }

    @Test
    fun `buildMergedText labels self and others`() {
        val participants = listOf(User(id = "u2", name = "Bob"))
        val text = ForwardMergePolicy.buildMergedText(
            listOf(message("m1", senderId = "u1", content = "one"), message("m2", senderId = "u2", content = "two")),
            "u1", "我", participants
        )
        assertEquals("我：one\nBob：two", text)
    }

    @Test
    fun `buildMergedText leaves bare content without sender label`() {
        val text = ForwardMergePolicy.buildMergedText(
            listOf(message("m1", senderId = "u2", content = "two"), message("m2", senderId = "u1", content = "one")),
            "u1", "我", null
        )
        assertEquals("two\n我：one", text)
    }

    @Test
    fun `buildMergedText truncates single and overall content at 4000`() {
        val long = "x".repeat(5_000)
        val single = ForwardMergePolicy.buildMergedText(
            listOf(message("m1", content = long), message("m2", content = "y")),
            "u1", "我", null
        )
        assertEquals(4_000, single.length)
        val overall = ForwardMergePolicy.buildMergedText(
            listOf(message("m1", content = "x".repeat(2_000)), message("m2", content = "y".repeat(2_000)), message("m3", content = "z".repeat(2_000))),
            "u1", "我", null
        )
        assertEquals(4_000, overall.length)
    }
}
