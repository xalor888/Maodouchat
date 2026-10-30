package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.chooseMessageVersion
import com.maodouchat.ui.screen.chatdetail.higherDeliveryStatus
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
        val tracker = com.maodouchat.messaging.v2.MessageMutationTracker()
        assertTrue(tracker.begin("m9", com.maodouchat.messaging.v2.MessageMutationKind.DELETE) != null)
        assertEquals(null, tracker.begin("m9", com.maodouchat.messaging.v2.MessageMutationKind.EDIT))
    }

    @Test
    fun `merged list is sorted by timestamp then id`() {
        val merged = mergeMessageVersions(
            listOf(base("m2").copy(timestamp = 2000L)),
            listOf(base("m1").copy(timestamp = 500L), base("m0").copy(timestamp = 500L))
        )
        assertEquals(listOf("m0", "m1", "m2"), merged.map { it.id })
    }

    @Test
    fun `terminal revoke is never resurrected by a later server edit`() {
        val revoked = base("m1").copy(type = MessageType.REVOKED)
        val edited = revoked.copy(type = MessageType.TEXT, content = "edited", editedAt = 99L)
        val result = chooseMessageVersion(revoked, edited)
        assertEquals(MessageType.REVOKED, result.type)
        assertEquals("text", result.content)
    }

    @Test
    fun `newer server edit wins content but keeps local READ`() {
        val local = base("m1").copy(status = MessageStatus.READ, editedAt = 10L, content = "old text")
        val server = local.copy(status = MessageStatus.SENT, editedAt = 20L, content = "new text")
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("new text", merged.content)
        assertEquals(20L, merged.editedAt)
        assertEquals(MessageStatus.READ, merged.status)
    }

    @Test
    fun `stale server edit cannot overwrite newer local edit`() {
        val local = base("m1").copy(editedAt = 20L, content = "newer", starred = true)
        val server = local.copy(editedAt = 10L, content = "older", starred = false)
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("newer", merged.content)
        assertEquals(20L, merged.editedAt)
        assertTrue(merged.starred)
    }

    @Test
    fun `FAILED is transparent in delivery status merge`() {
        assertEquals(MessageStatus.DELIVERED, higherDeliveryStatus(MessageStatus.FAILED, MessageStatus.DELIVERED))
        assertEquals(MessageStatus.SENT, higherDeliveryStatus(MessageStatus.SENT, MessageStatus.FAILED))
        assertEquals(MessageStatus.READ, higherDeliveryStatus(MessageStatus.DELIVERED, MessageStatus.READ))
    }

    @Test
    fun `fromWire is case-insensitive but does not trim`() {
        assertEquals(MessageStatus.READ, MessageStatus.fromWire("READ"))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.fromWire("Delivered"))
        assertEquals(MessageStatus.SENT, MessageStatus.fromWire(""))
        assertEquals(MessageStatus.SENT, MessageStatus.fromWire(" read "))
    }

    @Test
    fun `canAdvanceTo same status and FAILED recovery edges`() {
        assertTrue(MessageStatus.READ.canAdvanceTo(MessageStatus.READ))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.READ))
        assertTrue(MessageStatus.SENT.canAdvanceTo(MessageStatus.READ))
        assertFalse(MessageStatus.READ.canAdvanceTo(MessageStatus.FAILED))
    }

    @Test
    fun `merge keeps local-only and server-only messages in timestamp order`() {
        val merged = mergeMessageVersions(
            listOf(base("m1")),
            listOf(base("m2").copy(timestamp = 500L))
        )
        assertEquals(listOf("m2", "m1"), merged.map { it.id })
        assertEquals(MessageStatus.SENT, merged.single { it.id == "m2" }.status)
    }

    @Test
    fun `newer server edit cannot overwrite readable plaintext with ciphertext`() {
        val local = base("m1").copy(content = "hello", editedAt = 10L)
        val server = local.copy(content = "eyJjaXBoZXJ0ZXh0IjoiYWJjIn0=", editedAt = 20L)
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("hello", merged.content)
        assertEquals(20L, merged.editedAt)
    }

    @Test
    fun `candidate revoke beats even a newer local edit`() {
        val local = base("m1").copy(content = "local newer", editedAt = 99L)
        val revoked = local.copy(type = MessageType.REVOKED, editedAt = 1L, content = "revoked")
        val merged = mergeMessageVersions(listOf(local), listOf(revoked)).single()
        assertEquals(MessageType.REVOKED, merged.type)
    }

    @Test
    fun `canAdvanceTo allows ladder skips but not backward steps`() {
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.READ))
        assertFalse(MessageStatus.SENT.canAdvanceTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.DELIVERED))
    }

    @Test
    fun `fromWire recognizes every status case-insensitively`() {
        assertEquals(MessageStatus.SENDING, MessageStatus.fromWire("sending"))
        assertEquals(MessageStatus.SENT, MessageStatus.fromWire("SeNt"))
        assertEquals(MessageStatus.FAILED, MessageStatus.fromWire("FAILED"))
        assertEquals(MessageStatus.FAILED, MessageStatus.fromWire("Failed"))
    }

    @Test
    fun `higherDeliveryStatus picks the higher rank and stays transparent for FAILED`() {
        assertEquals(MessageStatus.SENT, higherDeliveryStatus(MessageStatus.SENDING, MessageStatus.SENT))
        assertEquals(MessageStatus.READ, higherDeliveryStatus(MessageStatus.READ, MessageStatus.SENDING))
        assertEquals(MessageStatus.SENT, higherDeliveryStatus(MessageStatus.SENT, MessageStatus.SENT))
        assertEquals(MessageStatus.FAILED, higherDeliveryStatus(MessageStatus.FAILED, MessageStatus.FAILED))
    }

    @Test
    fun `newer edit can clear star`() {
        val local = base("m1").copy(editedAt = 10L, starred = true, content = "old")
        val server = local.copy(editedAt = 20L, starred = false, content = "new")
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("new", merged.content)
        assertEquals(20L, merged.editedAt)
        assertFalse(merged.starred)
    }

    @Test
    fun `merge sorts equal timestamps by id`() {
        val merged = mergeMessageVersions(listOf(base("b"), base("a")), emptyList())
        assertEquals(listOf("a", "b"), merged.map { it.id })
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

    @Test
    fun `DELIVERED cannot advance to FAILED but FAILED can advance to DELIVERED`() {
        assertFalse(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.DELIVERED))
    }

    @Test
    fun `equal revision keeps plaintext when server sends MD envelope`() {
        val local = base("m1").copy(content = "hello")
        val server = local.copy(content = "MD:0123456789abcdef")
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("hello", merged.content)
    }

    @Test
    fun `equal revision keeps current when both sides are decrypt placeholders`() {
        val local = base("m1").copy(content = "解密失败")
        val server = local.copy(content = "[无法解密的消息]")
        val merged = mergeMessageVersions(listOf(local), listOf(server)).single()
        assertEquals("解密失败", merged.content)
    }

    @Test
    fun `both revoked versions resolve to candidate`() {
        val current = base("m1").copy(type = MessageType.REVOKED, content = "old revoked")
        val candidate = current.copy(content = "new revoked")
        val result = chooseMessageVersion(current, candidate)
        assertEquals(MessageType.REVOKED, result.type)
        assertEquals("new revoked", result.content)
    }

    @Test
    fun `merge of empty lists is empty and local-only list passes through`() {
        assertTrue(mergeMessageVersions(emptyList(), emptyList()).isEmpty())
        val merged = mergeMessageVersions(listOf(base("m1")), emptyList())
        assertEquals(listOf("m1"), merged.map { it.id })
        assertEquals("text", merged.single().content)
    }
}
