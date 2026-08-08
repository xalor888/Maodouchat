package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.ui.screen.chatdetail.MessageMutationKind
import com.maodouchat.ui.screen.chatdetail.MessageMutationTracker
import com.maodouchat.ui.screen.chatdetail.isAlreadyTerminalMutation
import com.maodouchat.ui.screen.chatdetail.isAmbiguousTransportFailure
import com.maodouchat.ui.screen.chatdetail.mergeMessageVersions
import com.maodouchat.ui.screen.chatdetail.toOptimisticEdit
import com.maodouchat.ui.screen.chatdetail.resolveDirectOutboxPeerId
import com.maodouchat.ui.screen.chatdetail.shouldMarkOutboxFailed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMutationPolicyTest {
    @Test
    fun `optimistic edit preserves delivery status`() {
        val read = message("m-status", "old", MessageType.TEXT).copy(status = MessageStatus.READ)

        val edited = read.toOptimisticEdit("new")

        assertEquals(MessageStatus.READ, edited.status)
    }

    @Test
    fun `optimistic edit advances from prior revision instead of device clock`() {
        val original = message("m-revision", "old", MessageType.TEXT).copy(
            timestamp = 1_000L,
            editedAt = 5_000L
        )

        val edited = original.toOptimisticEdit("new")

        assertEquals(5_001L, edited.editedAt)
    }

    @Test
    fun `authoritative delete prevents lost response rollback and late resurrection`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.DELETE)!!

        tracker.observeAuthoritative("m1", MessageMutationKind.DELETE)

        assertFalse(tracker.shouldRollback(ticket))
        assertTrue(tracker.shouldDrop("m1"))
        assertNull(tracker.begin("m1", MessageMutationKind.EDIT))
    }

    @Test
    fun `failed unconfirmed mutation rolls back exactly once`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.EDIT)!!

        assertTrue(tracker.shouldRollback(ticket))
        assertFalse(tracker.shouldRollback(ticket))
    }

    @Test
    fun `second concurrent mutation of same message is rejected`() {
        val tracker = MessageMutationTracker()
        assertTrue(tracker.begin("m1", MessageMutationKind.EDIT) != null)
        assertNull(tracker.begin("m1", MessageMutationKind.DELETE))
    }

    @Test
    fun `revoked version cannot be replaced by late original message`() {
        val revoked = message("m1", "revoked", MessageType.REVOKED)
        val lateOriginal = message("m1", "secret", MessageType.TEXT)

        val merged = mergeMessageVersions(listOf(revoked), listOf(lateOriginal)).single()

        assertEquals(MessageType.REVOKED, merged.type)
        assertEquals("revoked", merged.content)
    }

    @Test
    fun `older unedited echo cannot overwrite newer edited content`() {
        val edited = message("m1", "new", MessageType.TEXT).copy(editedAt = 200L)
        val lateOriginal = message("m1", "old", MessageType.TEXT)

        assertEquals("new", mergeMessageVersions(listOf(edited), listOf(lateOriginal)).single().content)
    }

    @Test
    fun `authoritative equal revision can clear star and reactions`() {
        val old = message("m1", "text", MessageType.TEXT).copy(
            editedAt = 200L,
            starred = true,
            reactions = listOf(MessageReaction("u1", "❤"))
        )
        val refreshed = old.copy(starred = false, reactions = emptyList())

        val merged = mergeMessageVersions(listOf(old), listOf(refreshed)).single()

        assertFalse(merged.starred)
        assertTrue(merged.reactions.isEmpty())
    }

    @Test
    fun `newer edit revision can clear star and reactions`() {
        val old = message("m1", "old", MessageType.TEXT).copy(
            editedAt = 100L,
            starred = true,
            reactions = listOf(MessageReaction("u1", "👍"))
        )
        val newer = message("m1", "new", MessageType.TEXT).copy(
            editedAt = 200L,
            starred = false,
            reactions = emptyList()
        )

        val merged = mergeMessageVersions(listOf(old), listOf(newer)).single()

        assertEquals("new", merged.content)
        assertFalse(merged.starred)
        assertTrue(merged.reactions.isEmpty())
    }

    @Test
    fun `older edit revision cannot clear newer star and reactions`() {
        val reactions = listOf(MessageReaction("u2", "🎉", reactedAt = 1L))
        val current = message("m1", "new", MessageType.TEXT).copy(
            editedAt = 200L,
            starred = true,
            reactions = reactions
        )
        val stale = message("m1", "old", MessageType.TEXT).copy(
            editedAt = 100L,
            starred = false,
            reactions = emptyList()
        )

        val merged = mergeMessageVersions(listOf(current), listOf(stale)).single()

        assertEquals("new", merged.content)
        assertTrue(merged.starred)
        assertEquals(reactions, merged.reactions)
    }

    @Test
    fun `equal revision keeps higher local delivery status`() {
        val localRead = message("m1", "text", MessageType.TEXT).copy(
            editedAt = 200L,
            status = MessageStatus.READ
        )
        val serverSent = localRead.copy(status = MessageStatus.SENT, starred = true)

        val merged = mergeMessageVersions(listOf(localRead), listOf(serverSent)).single()

        assertEquals(MessageStatus.READ, merged.status)
        assertTrue(merged.starred)
    }

    @Test
    fun `ws revoke confirmation cancels pending rest rollback`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m2", MessageMutationKind.REVOKE)!!
        tracker.observeAuthoritative("m2", MessageMutationKind.REVOKE)
        assertFalse(tracker.shouldRollback(ticket))
        assertTrue(tracker.shouldRenderRevoked("m2"))
    }

    @Test
    fun `ambiguous transport covers network timeout and 5xx`() {
        assertTrue(isAmbiguousTransportFailure(null))
        assertTrue(isAmbiguousTransportFailure(IllegalStateException("x")))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.NETWORK)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.TIMEOUT)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 503)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 408)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 403)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 400)))
    }

    @Test
    fun `already terminal mutation is http 404 only`() {
        assertTrue(isAlreadyTerminalMutation(ApiException(ApiFailureKind.HTTP, statusCode = 404)))
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.HTTP, statusCode = 403)))
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.NETWORK)))
        assertFalse(isAlreadyTerminalMutation(null))
    }

    @Test
    fun `outbox marks definitive rejects failed but keeps transport ambiguity pending`() {
        assertTrue(shouldMarkOutboxFailed(ApiException(ApiFailureKind.HTTP, statusCode = 403)))
        assertTrue(shouldMarkOutboxFailed(ApiException(ApiFailureKind.HTTP, statusCode = 400)))
        assertTrue(shouldMarkOutboxFailed(IllegalStateException("encrypt")))
        assertTrue(shouldMarkOutboxFailed(IllegalStateException("outbox_peer_unresolved")))
        assertFalse(shouldMarkOutboxFailed(ApiException(ApiFailureKind.NETWORK)))
        assertFalse(shouldMarkOutboxFailed(ApiException(ApiFailureKind.TIMEOUT)))
        assertFalse(shouldMarkOutboxFailed(ApiException(ApiFailureKind.HTTP, statusCode = 503)))
        assertFalse(shouldMarkOutboxFailed(ApiException(ApiFailureKind.HTTP, statusCode = 409)))
        assertFalse(shouldMarkOutboxFailed(null))
    }

    @Test
    fun `sender key coverage transient failure keeps outbox pending`() {
        // 8.41：SK 覆盖的瞬态网络失败（TransientCoverageException）不得标 FAILED——
        // 群消息在 SK 分发阶段的弱网失败应保持 SENDING 待 flusher 重试
        assertFalse(
            shouldMarkOutboxFailed(
                com.maodouchat.crypto.TransientCoverageException("sender key coverage transient failure")
            )
        )
        assertTrue(
            shouldMarkOutboxFailed(
                IllegalStateException("sender_key_epoch_changed")
            )
        )
    }

    @Test
    fun `outbox peer prefers active contact then chat participants and never self`() {
        val peer = User(id = "peer-1", name = "Peer")
        val self = User(id = "me", name = "Me")
        assertEquals(
            "peer-1",
            resolveDirectOutboxPeerId(
                chatId = "c1",
                activeChatId = "c1",
                activeContactId = "peer-1",
                selfUserId = "me",
                chatParticipants = listOf(self, peer)
            )
        )
        // Active contact not ready (empty / self): fall back to participants.
        assertEquals(
            "peer-1",
            resolveDirectOutboxPeerId(
                chatId = "c1",
                activeChatId = "c1",
                activeContactId = "",
                selfUserId = "me",
                chatParticipants = listOf(self, peer)
            )
        )
        // Flushing another chat must not use the open chat's contact.
        assertEquals(
            "peer-2",
            resolveDirectOutboxPeerId(
                chatId = "c2",
                activeChatId = "c1",
                activeContactId = "peer-1",
                selfUserId = "me",
                chatParticipants = listOf(User(id = "peer-2", name = ""))
            )
        )
        // No recoverable peer → null so flush can mark FAILED instead of silent skip.
        assertNull(
            resolveDirectOutboxPeerId(
                chatId = "c1",
                activeChatId = "c1",
                activeContactId = "me",
                selfUserId = "me",
                chatParticipants = listOf(self)
            )
        )
        assertNull(
            resolveDirectOutboxPeerId(
                chatId = "c1",
                activeChatId = "c1",
                activeContactId = null,
                selfUserId = "me",
                chatParticipants = emptyList()
            )
        )
    }

    @Test
    fun `completed ticket does not rollback twice`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m3", MessageMutationKind.EDIT)!!
        assertTrue(tracker.complete(ticket))
        assertFalse(tracker.shouldRollback(ticket))
    }

    @Test
    fun `cancel rollback clears pending delete so sync is not permanently blocked`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m-cancel", MessageMutationKind.DELETE)!!
        // In-flight optimistic delete temporarily drops the message from merge paths.
        assertTrue(tracker.shouldDrop("m-cancel"))

        // Cancellation path must clear the ticket (same as business failure rollback).
        assertTrue(tracker.shouldRollback(ticket))
        assertFalse(tracker.shouldDrop("m-cancel"))
        assertFalse(tracker.shouldRenderRevoked("m-cancel"))
        // A later user action / retry must be allowed after cancel.
        assertTrue(tracker.begin("m-cancel", MessageMutationKind.DELETE) != null)
    }

    @Test
    fun `cancel rollback clears pending revoke render flag`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m-rev", MessageMutationKind.REVOKE)!!
        assertTrue(tracker.shouldRenderRevoked("m-rev"))
        assertTrue(tracker.shouldRollback(ticket))
        assertFalse(tracker.shouldRenderRevoked("m-rev"))
        assertTrue(tracker.begin("m-rev", MessageMutationKind.EDIT) != null)
    }

    private fun message(id: String, content: String, type: MessageType) = Message(
        id = id,
        chatId = "chat-1",
        senderId = "user-1",
        content = content,
        type = type,
        timestamp = 100L,
        status = MessageStatus.SENT
    )
}
