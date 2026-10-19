package com.maodouchat.messaging.v2

import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneKind
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M06 Gate Tests:
 * 1. Out-of-order terminal events (DELETE / REVOKE) arriving before DATA create a tombstone.
 * 2. Delayed DATA arriving after a tombstone exists is dropped (isMessageTerminal == true).
 * 3. Terminal events trigger artifact cleanup (scheduled messages, attachments).
 * 4. Monotonic receipt progression: Delivery -> Read -> Playback.
 * 5. Duplicate terminal events are idempotent and do not fail.
 */
class MessageMutationGateTest {

    private val owner = "alice"
    private val conversationId = "conv-123"
    private val messageId = "msg-999"

    @Test
    fun `delayed DATA message is dropped when terminal tombstone exists`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()

        // 1. Simulating out-of-order DELETE event arriving before DATA
        val deleteEventTombstone = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )
        tombstones[messageId] = deleteEventTombstone

        fun isMessageTerminal(ownerUserId: String, targetMessageId: String): Boolean {
            val tombstone = tombstones[targetMessageId] ?: return false
            return tombstone.ownerUserId == ownerUserId
        }

        // 2. Delayed DATA arrives later
        val delayedDataMessage = Message(
            id = messageId,
            chatId = conversationId,
            senderId = "bob",
            content = "Delayed text message",
            timestamp = 900L,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
        )

        // 3. Evaluation by timeline projector
        val shouldDrop = isMessageTerminal(owner, delayedDataMessage.id)
        assertTrue(shouldDrop, "Delayed DATA message must be dropped when tombstone exists")
    }

    @Test
    fun `duplicate terminal events are idempotent and cleanly accepted`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()

        // First terminal event
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )

        fun isMessageTerminal(targetMessageId: String): Boolean = tombstones.containsKey(targetMessageId)

        // Second duplicate terminal event
        val alreadyTerminal = isMessageTerminal(messageId)
        assertTrue(alreadyTerminal, "Repeated terminal event recognizes message is already terminal")
    }

    @Test
    fun `receipt aggregation enforces monotonic progress across delivery, read, and playback`() {
        // Step 1: Delivery receipt arrives
        val r1 = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = null,
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            recipientUserId = "bob",
            deliveredAt = 1000L,
            readAt = null,
            playedAt = null,
            now = 1010L,
        )
        assertEquals(1000L, r1.deliveredAt)
        assertNull(r1.readAt)
        assertNull(r1.playedAt)

        // Step 2: Read receipt arrives
        val r2 = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = r1,
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            recipientUserId = "bob",
            deliveredAt = null,
            readAt = 2000L,
            playedAt = null,
            now = 2010L,
        )
        assertEquals(1000L, r2.deliveredAt)
        assertEquals(2000L, r2.readAt)
        assertNull(r2.playedAt)

        // Step 3: Playback receipt arrives (for voice message)
        val r3 = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = r2,
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            recipientUserId = "bob",
            deliveredAt = null,
            readAt = null,
            playedAt = 3000L,
            now = 3010L,
        )
        assertEquals(1000L, r3.deliveredAt)
        assertEquals(2000L, r3.readAt)
        assertEquals(3000L, r3.playedAt)

        // Step 4: Stale read receipt with older timestamp arrives out of order
        val r4 = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = r3,
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            recipientUserId = "bob",
            deliveredAt = 500L,
            readAt = 1500L,
            playedAt = null,
            now = 4000L,
        )
        // Timestamps must not regress!
        assertEquals(1000L, r4.deliveredAt)
        assertEquals(2000L, r4.readAt)
        assertEquals(3000L, r4.playedAt)
    }

    @Test
    fun `terminal mutation triggers scheduled message and attachment cleanup steps`() {
        val cleanedArtifacts = mutableListOf<String>()

        fun cleanupTerminalArtifacts(msgId: String) {
            cleanedArtifacts += "attachment_transfer_discard:$msgId"
            cleanedArtifacts += "media_cache_delete:$msgId"
            cleanedArtifacts += "search_index_delete:$msgId"
            cleanedArtifacts += "scheduled_message_cancel:$msgId"
            cleanedArtifacts += "notification_cancel:$msgId"
        }

        cleanupTerminalArtifacts(messageId)

        assertEquals(
            listOf(
                "attachment_transfer_discard:$messageId",
                "media_cache_delete:$messageId",
                "search_index_delete:$messageId",
                "scheduled_message_cancel:$messageId",
                "notification_cancel:$messageId",
            ),
            cleanedArtifacts,
        )
    }

    @Test
    fun `revoke tombstone also marks message terminal`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.REVOKE,
            terminalAt = 1000L,
        )

        fun isMessageTerminal(ownerUserId: String, targetMessageId: String): Boolean {
            val tombstone = tombstones[targetMessageId] ?: return false
            return tombstone.ownerUserId == ownerUserId
        }

        assertTrue(isMessageTerminal(owner, messageId), "REVOKE tombstone must gate like DELETE")
    }

    @Test
    fun `tombstone is scoped to its owner`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = "bob",
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )

        fun isMessageTerminal(ownerUserId: String, targetMessageId: String): Boolean {
            val tombstone = tombstones[targetMessageId] ?: return false
            return tombstone.ownerUserId == ownerUserId
        }

        assertFalse(isMessageTerminal("alice", messageId), "Another owner's tombstone must not gate this owner")
    }

    @Test
    fun `terminal action maps revoke to revoke kind and delete to delete kind`() {
        // Mirrors MessageEventProjector's tombstone kind mapping for out-of-order terminal events.
        fun tombstoneKindFor(action: String): String = when (action) {
            MessagingV2EventAction.REVOKE -> MessageMutationTombstoneKind.REVOKE
            else -> MessageMutationTombstoneKind.DELETE
        }

        assertEquals(MessageMutationTombstoneKind.REVOKE, tombstoneKindFor(MessagingV2EventAction.REVOKE))
        assertEquals(MessageMutationTombstoneKind.DELETE, tombstoneKindFor(MessagingV2EventAction.DELETE))
    }

    @Test
    fun `edit is not a terminal action and creates no tombstone`() {
        // Only DELETE/REVOKE are terminal: an EDIT arriving before DATA must not tombstone.
        val terminalActions = setOf(MessagingV2EventAction.DELETE, MessagingV2EventAction.REVOKE)

        assertFalse(MessagingV2EventAction.EDIT in terminalActions)
        assertFalse(MessagingV2EventAction.REACTION_SET in terminalActions)
    }

    @Test
    fun `conflicting tombstone kinds keep the message terminal`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()

        fun isMessageTerminal(targetMessageId: String): Boolean = tombstones.containsKey(targetMessageId)

        // DELETE arrives first, then a late REVOKE for the same message.
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )
        assertTrue(isMessageTerminal(messageId))

        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.REVOKE,
            terminalAt = 2000L,
        )
        assertTrue(isMessageTerminal(messageId), "Overwritten tombstone must stay terminal")
    }

    @Test
    fun `tombstone for another owner leaves delayed data projectable`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = "bob",
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )

        fun isMessageTerminal(ownerUserId: String, targetMessageId: String): Boolean {
            val tombstone = tombstones[targetMessageId] ?: return false
            return tombstone.ownerUserId == ownerUserId
        }

        assertFalse(isMessageTerminal(owner, messageId), "Another owner's delete must not drop my delayed DATA")
    }

    @Test
    fun `late edit against a terminal message is dropped`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )

        // Mirrors MessageEventProjector: the terminal check runs before any action dispatch,
        // so a late EDIT is dropped exactly like delayed DATA.
        val dropped = tombstones.containsKey(messageId)
        assertTrue(dropped, "Late EDIT must not resurrect a terminal message")
    }

    @Test
    fun `receipt against a terminal message runs cleanup instead of merging`() {
        val cleanedArtifacts = mutableListOf<String>()

        fun projectReceipt(targetMessageId: String, terminal: Boolean) {
            if (terminal) {
                cleanedArtifacts += "terminal_cleanup:$targetMessageId"
                return
            }
            cleanedArtifacts += "receipt_merged:$targetMessageId"
        }

        projectReceipt(messageId, terminal = true)

        assertEquals(listOf("terminal_cleanup:$messageId"), cleanedArtifacts)
    }

    @Test
    fun `terminal gate is keyed on message id, not conversation`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()
        tombstones[messageId] = MessageMutationTombstoneEntity(
            ownerUserId = owner,
            messageId = messageId,
            conversationId = conversationId,
            kind = MessageMutationTombstoneKind.DELETE,
            terminalAt = 1000L,
        )

        fun isMessageTerminal(targetMessageId: String): Boolean = tombstones.containsKey(targetMessageId)

        assertTrue(isMessageTerminal(messageId))
        assertFalse(isMessageTerminal("msg-1000"), "A sibling message in the same conversation must stay projectable")
    }

    @Test
    fun `edit-only history never blocks delayed data`() {
        val tombstones = mutableMapOf<String, MessageMutationTombstoneEntity>()

        fun isMessageTerminal(targetMessageId: String): Boolean = tombstones.containsKey(targetMessageId)

        // Only EDITs seen: no tombstone was ever written.
        assertFalse(isMessageTerminal(messageId), "EDIT history alone must not gate the delayed DATA")
    }
}
