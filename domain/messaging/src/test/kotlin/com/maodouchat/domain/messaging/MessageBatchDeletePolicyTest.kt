package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageBatchDeletePolicyTest {

    private fun msg(id: String, senderId: String) =
        Message(id = id, chatId = "c1", senderId = senderId, content = "hi")

    @Test
    fun `only own messages are deletable`() {
        val selected = listOf(msg("m1", "me"), msg("m2", "other"), msg("m3", "me"))
        val result = MessageBatchDeletePolicy.resolve(selected, "me")
        assertEquals(listOf("m1", "m3"), result.cappedIds)
        assertEquals(2, result.cappedCount)
        assertEquals(0, result.cappedOut)
        assertEquals(1, result.skippedCount)
        assertTrue(result.canConfirm)
    }

    @Test
    fun `batch capped at 60 with overflow counted`() {
        val selected = (1..65).map { msg("m$it", "me") }
        val result = MessageBatchDeletePolicy.resolve(selected, "me")
        assertEquals(60, result.cappedIds.size)
        assertEquals((1..60).map { "m$it" }, result.cappedIds)
        assertEquals(5, result.cappedOut)
        assertEquals(0, result.skippedCount)
        assertTrue(result.canConfirm)
    }

    @Test
    fun `exactly 60 has no overflow`() {
        val selected = (1..60).map { msg("m$it", "me") }
        val result = MessageBatchDeletePolicy.resolve(selected, "me")
        assertEquals(0, result.cappedOut)
        assertTrue(result.canConfirm)
    }

    @Test
    fun `no own messages cannot confirm`() {
        val result = MessageBatchDeletePolicy.resolve(listOf(msg("m1", "other")), "me")
        assertTrue(result.cappedIds.isEmpty())
        assertEquals(0, result.cappedCount)
        assertEquals(1, result.skippedCount)
        assertFalse(result.canConfirm)
    }
}
