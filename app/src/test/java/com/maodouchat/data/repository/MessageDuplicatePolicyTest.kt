package com.maodouchat.data.repository

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDuplicatePolicyTest {

    @Test
    fun sameIdUnchangedIsRedundant() {
        val existing = base("srv-1")
        assertTrue(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy()))
        assertFalse(
            MessageDuplicatePolicy.isRedundantWrite(
                existing,
                existing.copy(status = MessageStatus.READ)
            )
        )
    }

    @Test
    fun differentIdSameDeliveryIsDuplicate() {
        val local = base("local-tmp")
        val remote = local.copy(id = "srv-1")
        assertTrue(MessageDuplicatePolicy.isSameDelivery(local, remote))
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, remote.copy(timestamp = 99L)))
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, remote.copy(chatId = "c2")))
        assertEquals("local-tmp", MessageDuplicatePolicy.pickCanonical(local, remote).id)
        assertEquals("hello", MessageDuplicatePolicy.pickCanonical(local, remote.copy(content = "hello")).content)
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = "hello",
        type = MessageType.TEXT,
        timestamp = 42L,
        status = MessageStatus.SENT
    )
}
