package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.messaging.v2.MessageMutationKind
import com.maodouchat.messaging.v2.MessageMutationTracker
import com.maodouchat.ui.screen.chatdetail.isAlreadyTerminalMutation
import com.maodouchat.ui.screen.chatdetail.isAmbiguousTransportFailure
import com.maodouchat.ui.screen.chatdetail.mergeMessageVersions
import com.maodouchat.ui.screen.chatdetail.toOptimisticEdit
import com.maodouchat.ui.screen.chatdetail.toRevokedPlaceholder
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
    fun `peer plaintext wins over later decrypt-failure placeholder`() {
        val plain = message("m-live", "hello_live_sweep", MessageType.TEXT)
        val failed = message("m-live", "[无法解密的消息]", MessageType.TEXT)

        val merged = mergeMessageVersions(listOf(plain), listOf(failed)).single()

        assertEquals("hello_live_sweep", merged.content)
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

    @Test
    fun `ambiguous transport covers 429 and 504 but not invalid or unexpected`() {
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 429)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 504)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.INVALID_RESPONSE)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.UNEXPECTED)))
    }

    @Test
    fun `already terminal mutation requires http kind even at 404`() {
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.TIMEOUT, statusCode = 404)))
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.HTTP, statusCode = 410)))
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.HTTP, statusCode = null)))
    }

    @Test
    fun `optimistic edit saturates at max revision instead of overflowing`() {
        val original = message("m-max", "old", MessageType.TEXT).copy(editedAt = Long.MAX_VALUE)

        val edited = original.toOptimisticEdit("new")

        assertEquals(Long.MAX_VALUE, edited.editedAt)
    }

    @Test
    fun `optimistic edit without prior revision advances from timestamp`() {
        val edited = message("m-norev", "old", MessageType.TEXT).toOptimisticEdit("new")

        assertEquals(101L, edited.editedAt)
    }

    @Test
    fun `tracker rejects blank id and authoritative edit does not block later mutations`() {
        val tracker = MessageMutationTracker()
        assertNull(tracker.begin("", MessageMutationKind.EDIT))
        assertNull(tracker.begin("   ", MessageMutationKind.EDIT))

        val ticket = tracker.begin("m-edit", MessageMutationKind.EDIT)!!
        tracker.observeAuthoritative("m-edit", MessageMutationKind.EDIT)
        assertFalse(tracker.shouldRollback(ticket))
        assertFalse(tracker.shouldDrop("m-edit"))
        assertFalse(tracker.shouldRenderRevoked("m-edit"))
        assertTrue(tracker.begin("m-edit", MessageMutationKind.DELETE) != null)
    }

    @Test
    fun `completed ticket allows a later mutation of the same message`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.EDIT)!!
        assertTrue(tracker.complete(ticket))
        assertTrue(tracker.begin("m1", MessageMutationKind.DELETE) != null)
    }

    @Test
    fun `authoritative delete wins over later revoke render`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.DELETE)!!
        tracker.observeAuthoritative("m1", MessageMutationKind.DELETE)
        tracker.observeAuthoritative("m1", MessageMutationKind.REVOKE)
        assertFalse(tracker.shouldRollback(ticket))
        assertTrue(tracker.shouldDrop("m1"))
        assertFalse(tracker.shouldRenderRevoked("m1"))
    }

    @Test
    fun `pending edit neither drops nor renders revoked`() {
        val tracker = MessageMutationTracker()
        tracker.begin("m1", MessageMutationKind.EDIT)!!
        assertFalse(tracker.shouldDrop("m1"))
        assertFalse(tracker.shouldRenderRevoked("m1"))
    }

    @Test
    fun `merge appends unknown incoming id sorted by timestamp`() {
        val existing = message("m2", "current", MessageType.TEXT)
        val incoming = message("m1", "incoming", MessageType.TEXT).copy(timestamp = 50L)
        val merged = mergeMessageVersions(listOf(existing), listOf(incoming))
        assertEquals(listOf("m1", "m2"), merged.map { it.id })
    }

    @Test
    fun `equal revision with both readable keeps candidate content`() {
        val current = message("m1", "old", MessageType.TEXT)
        val candidate = message("m1", "new", MessageType.TEXT)
        val merged = mergeMessageVersions(listOf(current), listOf(candidate)).single()
        assertEquals("new", merged.content)
    }

    @Test
    fun `optimistic edit advances from timestamp when prior revision is older`() {
        val original = message("m-oldrev", "old", MessageType.TEXT).copy(
            timestamp = 1_000L,
            editedAt = 500L
        )

        val edited = original.toOptimisticEdit("new")

        assertEquals(1_001L, edited.editedAt)
        assertEquals("new", edited.content)
        assertEquals("m-oldrev", edited.id)
        assertEquals(MessageType.TEXT, edited.type)
    }

    @Test
    fun `optimistic edit at timestamp max saturates without prior revision`() {
        val original = message("m-tsmax", "old", MessageType.TEXT).copy(timestamp = Long.MAX_VALUE)

        val edited = original.toOptimisticEdit("new")

        assertEquals(Long.MAX_VALUE, edited.editedAt)
        assertEquals("new", edited.content)
    }

    @Test
    fun `ambiguous transport covers 500 and 502 but not 404`() {
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 500)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 502)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 404)))
    }

    @Test
    fun `rollback of edit ticket frees message for delete`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m-free", MessageMutationKind.EDIT)!!

        assertTrue(tracker.shouldRollback(ticket))
        assertTrue(tracker.begin("m-free", MessageMutationKind.DELETE) != null)
        assertTrue(tracker.shouldDrop("m-free"))
    }

    @Test
    fun `revoked placeholder replaces content and clears meta`() {
        val original = message("m-revoked", "hello", MessageType.TEXT).copy(
            status = MessageStatus.READ,
            meta = MessageMeta(mentions = listOf("u1"), replyToId = "m0")
        )

        val placeholder = original.toRevokedPlaceholder("已撤回")

        assertEquals("已撤回", placeholder.content)
        assertEquals(MessageType.REVOKED, placeholder.type)
        assertEquals(MessageMeta(), placeholder.meta)
        assertEquals(MessageStatus.READ, placeholder.status)
        assertEquals("m-revoked", placeholder.id)
    }

    @Test
    fun `optimistic edit preserves star reactions and timestamp`() {
        val original = message("m-star", "old", MessageType.TEXT).copy(
            timestamp = 1_000L,
            editedAt = 500L,
            starred = true,
            reactions = listOf(MessageReaction("u1", "❤"))
        )

        val edited = original.toOptimisticEdit("new")

        assertEquals("new", edited.content)
        assertEquals(1_001L, edited.editedAt)
        assertEquals(1_000L, edited.timestamp)
        assertTrue(edited.starred)
        assertEquals(listOf(MessageReaction("u1", "❤")), edited.reactions)
    }

    @Test
    fun `tracker rejects blank id for delete and revoke`() {
        val tracker = MessageMutationTracker()
        assertNull(tracker.begin("", MessageMutationKind.DELETE))
        assertNull(tracker.begin("   ", MessageMutationKind.REVOKE))
        assertTrue(tracker.begin("m-ok", MessageMutationKind.DELETE) != null)
    }

    @Test
    fun `ambiguous transport covers other 5xx but not 3xx or missing code`() {
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 501)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 507)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 302)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 418)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = null)))
    }

    @Test
    fun `already terminal mutation rejects non-http 404`() {
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.NETWORK, statusCode = 404)))
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.INVALID_RESPONSE, statusCode = 404)))
        assertFalse(isAlreadyTerminalMutation(ApiException(ApiFailureKind.UNEXPECTED, statusCode = 404)))
        assertTrue(isAlreadyTerminalMutation(ApiException(ApiFailureKind.HTTP, statusCode = 404)))
    }

    @Test
    fun `revoked placeholder preserves timestamp sender and chat`() {
        val original = message("m-keep", "hello", MessageType.TEXT).copy(
            timestamp = 100L,
            status = MessageStatus.READ
        )

        val placeholder = original.toRevokedPlaceholder("已撤回")

        assertEquals(100L, placeholder.timestamp)
        assertEquals("user-1", placeholder.senderId)
        assertEquals("chat-1", placeholder.chatId)
        assertEquals("已撤回", placeholder.content)
    }

    @Test
    fun `authoritative revoke still allows a later edit and keeps the render flag`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.REVOKE)!!
        tracker.observeAuthoritative("m1", MessageMutationKind.REVOKE)

        assertTrue(tracker.shouldRenderRevoked("m1"))
        assertTrue(tracker.begin("m1", MessageMutationKind.EDIT) != null)
        assertTrue(tracker.shouldRenderRevoked("m1"))
    }

    @Test
    fun `authoritatively observed ticket neither completes nor rolls back`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.EDIT)!!
        tracker.observeAuthoritative("m1", MessageMutationKind.EDIT)

        assertFalse(tracker.complete(ticket))
        assertFalse(tracker.shouldRollback(ticket))
        assertTrue(tracker.begin("m1", MessageMutationKind.DELETE) != null)
    }

    @Test
    fun `completed delete ticket allows a later edit and stops dropping`() {
        val tracker = MessageMutationTracker()
        val ticket = tracker.begin("m1", MessageMutationKind.DELETE)!!
        assertTrue(tracker.shouldDrop("m1"))

        assertTrue(tracker.complete(ticket))
        assertFalse(tracker.shouldDrop("m1"))
        assertTrue(tracker.begin("m1", MessageMutationKind.EDIT) != null)
        assertFalse(tracker.shouldRenderRevoked("m1"))
    }

    @Test
    fun `newer wire edit keeps plaintext but takes revision and star`() {
        val current = message("m1", "hello", MessageType.TEXT).copy(editedAt = 100L, starred = false)
        val wire = message(
            "m1",
            """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}""",
            MessageType.TEXT
        ).copy(editedAt = 200L, starred = true)

        val merged = mergeMessageVersions(listOf(current), listOf(wire)).single()

        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
        assertTrue(merged.starred)
    }

    @Test
    fun `ambiguous transport covers 505 511 599 but not 499`() {
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 505)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 511)))
        assertTrue(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 599)))
        assertFalse(isAmbiguousTransportFailure(ApiException(ApiFailureKind.HTTP, statusCode = 499)))
    }

    @Test
    fun `optimistic edit at zero timestamp advances to one`() {
        val original = message("m-zero", "old", MessageType.TEXT).copy(timestamp = 0L)

        val edited = original.toOptimisticEdit("new")

        assertEquals("new", edited.content)
        assertEquals(1L, edited.editedAt)
        assertEquals(0L, edited.timestamp)
    }

    @Test
    fun `revoked placeholder preserves star and reactions`() {
        val reaction = MessageReaction("u1", "❤", reactedAt = 7L)
        val original = message("m-rev", "hello", MessageType.TEXT).copy(
            starred = true,
            reactions = listOf(reaction)
        )

        val placeholder = original.toRevokedPlaceholder("已撤回")

        assertEquals(MessageType.REVOKED, placeholder.type)
        assertEquals(MessageMeta(), placeholder.meta)
        assertTrue(placeholder.starred)
        assertEquals(listOf(reaction), placeholder.reactions)
    }

    @Test
    fun `merge equal timestamps sort by id`() {
        val later = message("m-b", "b", MessageType.TEXT).copy(timestamp = 100L)
        val earlier = message("m-a", "a", MessageType.TEXT).copy(timestamp = 100L)

        val merged = mergeMessageVersions(listOf(later, earlier), emptyList())

        assertEquals(listOf("m-a", "m-b"), merged.map { it.id })
    }

    @Test
    fun `tracker pending edit does not block delete of another message`() {
        val tracker = MessageMutationTracker()
        assertTrue(tracker.begin("m1", MessageMutationKind.EDIT) != null)
        assertTrue(tracker.begin("m2", MessageMutationKind.DELETE) != null)
        assertTrue(tracker.shouldDrop("m2"))
        assertFalse(tracker.shouldDrop("m1"))
    }

    @Test
    fun `merge revoked candidate wins regardless of older edit revision`() {
        val current = message("m1", "old", MessageType.TEXT).copy(editedAt = 500L)
        val candidate = message("m1", "已撤回", MessageType.REVOKED).copy(editedAt = null)

        val merged = mergeMessageVersions(listOf(current), listOf(candidate)).single()

        assertEquals(MessageType.REVOKED, merged.type)
        assertEquals("已撤回", merged.content)
    }
}
