package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.mergeMessageVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1-04: delivery ladder + merge must never regress READ/DELIVERED or resurrect terminal failures oddly.
 */
class MessageStatusMachineTest {
    @Test
    fun `delivery ladder only advances forward`() {
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.SENT))
        assertTrue(MessageStatus.SENT.canAdvanceTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.READ))
        assertFalse(MessageStatus.READ.canAdvanceTo(MessageStatus.SENT))
        assertFalse(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.SENT.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.SENT))
    }

    @Test
    fun `fromWire defaults unknown to SENT so groups do not paint a false double-check`() {
        assertEquals(MessageStatus.SENT, MessageStatus.fromWire(null))
        assertEquals(MessageStatus.SENT, MessageStatus.fromWire("not-a-status"))
        assertEquals(MessageStatus.READ, MessageStatus.fromWire("read"))
    }

    @Test
    fun `merge never regresses local READ when server echoes SENT`() {
        val local = base("m1").copy(status = MessageStatus.READ, editedAt = 10L)
        val server = local.copy(status = MessageStatus.SENT)
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals(MessageStatus.READ, merged.status)
    }

    @Test
    fun `merge upgrades SENDING to DELIVERED from server`() {
        val local = base("m1").copy(status = MessageStatus.SENDING)
        val server = local.copy(status = MessageStatus.DELIVERED)
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals(MessageStatus.DELIVERED, merged.status)
    }

    @Test
    fun `FAILED local is replaced by successful server SENT`() {
        val local = base("m1").copy(status = MessageStatus.FAILED)
        val server = local.copy(status = MessageStatus.SENT)
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals(MessageStatus.SENT, merged.status)
    }

    @Test
    fun `plaintext is preferred over ciphertext envelope on equal revision`() {
        val local = base("m1").copy(content = "hello friend", status = MessageStatus.READ)
        val server = local.copy(
            content = "eyJjaXBoZXJ0ZXh0IjoiYWJjIn0=",
            status = MessageStatus.SENT
        )
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("hello friend", merged.content)
        assertEquals(MessageStatus.READ, merged.status)
    }

    @Test
    fun `delete ticket still blocks concurrent edit begin`() {
        val tracker = com.maodouchat.ui.screen.chatdetail.MessageMutationTracker()
        assertTrue(tracker.begin("m9", com.maodouchat.ui.screen.chatdetail.MessageMutationKind.DELETE) != null)
        assertEquals(null, tracker.begin("m9", com.maodouchat.ui.screen.chatdetail.MessageMutationKind.EDIT))
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "chat-1",
        senderId = "user-1",
        content = "text",
        type = MessageType.TEXT,
        timestamp = 1_000L,
        status = MessageStatus.SENT
    )
}
