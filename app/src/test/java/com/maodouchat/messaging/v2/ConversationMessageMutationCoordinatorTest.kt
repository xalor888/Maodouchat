package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationMessageMutationCoordinatorTest {
    @Test
    fun `precommit failure rolls optimistic projection back`() = runTest {
        val failure = IllegalStateException("outbox unavailable")
        val coordinator = coordinator(enqueue = { _, _, _ -> throw failure })
        val original = message()
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.delete(original, OWNER, null, projections::add)

        assertEquals(
            listOf(
                MessageMutationProjection.Remove(MESSAGE_ID),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertSame(failure, failed.error)
        assertTrue(failed.rolledBack)
    }

    @Test
    fun `postcommit local failure keeps optimistic projection`() = runTest {
        val failure = IllegalStateException("sqlcipher unavailable")
        val coordinator = coordinator(persistDeleted = { throw failure })
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.delete(message(), OWNER, null, projections::add)

        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Remove(MESSAGE_ID)),
            projections,
        )
        val applied = assertIs<ConversationMessageMutationOutcome.Applied>(outcome)
        val projectionError = assertIs<IllegalStateException>(applied.localProjectionError)
        assertEquals(failure.message, projectionError.message)
        assertTrue(applied.removePinnedReference)
    }

    @Test
    fun `cancellation before commit rolls back and is rethrown`() = runTest {
        val coordinator = coordinator(
            enqueue = { _, _, _ -> throw CancellationException("cancel before commit") },
        )
        val original = message()
        val projections = mutableListOf<MessageMutationProjection>()

        assertFailsWith<CancellationException> {
            coordinator.edit(
                original = original,
                updated = original.copy(content = "edited"),
                ownerUserId = OWNER,
                groupRevision = null,
                project = projections::add,
            )
        }

        assertEquals(
            listOf<MessageMutationProjection>(
                MessageMutationProjection.Set(original.copy(content = "edited")),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
    }

    @Test
    fun `concurrent duplicate mutation is ignored`() = runTest {
        val enteredOutbox = CompletableDeferred<Unit>()
        val releaseOutbox = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            enqueue = { _, _, _ ->
                enteredOutbox.complete(Unit)
                releaseOutbox.await()
            },
        )
        val original = message()
        val firstProjections = mutableListOf<MessageMutationProjection>()
        val first = async {
            coordinator.delete(original, OWNER, null, firstProjections::add)
        }
        enteredOutbox.await()

        val duplicate = coordinator.revoke(
            original = original,
            revoked = original.copy(type = MessageType.REVOKED),
            ownerUserId = OWNER,
            groupRevision = null,
            project = { error("duplicate must not project") },
        )
        releaseOutbox.complete(Unit)

        assertIs<ConversationMessageMutationOutcome.Ignored>(duplicate)
        assertIs<ConversationMessageMutationOutcome.Applied>(first.await())
        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Remove(MESSAGE_ID)),
            firstProjections,
        )
    }

    @Test
    fun `authoritative terminal observation prevents stale failure resurrection`() = runTest {
        val enteredOutbox = CompletableDeferred<Unit>()
        val rejectOutbox = CompletableDeferred<Unit>()
        val failure = IllegalStateException("lost response")
        val coordinator = coordinator(
            enqueue = { _, _, _ ->
                enteredOutbox.complete(Unit)
                rejectOutbox.await()
                throw failure
            },
        )
        val projections = mutableListOf<MessageMutationProjection>()
        val pending = async {
            coordinator.delete(message(), OWNER, null, projections::add)
        }
        enteredOutbox.await()

        coordinator.observeAuthoritative(MESSAGE_ID, MessageMutationKind.DELETE)
        rejectOutbox.complete(Unit)

        val outcome = assertIs<ConversationMessageMutationOutcome.Applied>(pending.await())
        assertTrue(outcome.authoritativeElsewhere)
        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Remove(MESSAGE_ID)),
            projections,
        )
    }

    @Test
    fun `edit after authoritative delete is ignored without projecting`() = runTest {
        val coordinator = coordinator()
        coordinator.observeAuthoritative(MESSAGE_ID, MessageMutationKind.DELETE)

        val outcome = coordinator.edit(
            original = message(),
            updated = message().copy(content = "edited"),
            ownerUserId = OWNER,
            groupRevision = null,
            project = { error("terminal delete must not project") },
        )

        assertIs<ConversationMessageMutationOutcome.Ignored>(outcome)
    }

    @Test
    fun `edit postcommit local failure keeps optimistic set and pinned reference`() = runTest {
        val failure = IllegalStateException("sqlcipher unavailable")
        val coordinator = coordinator(persistEdited = { throw failure })
        val updated = message().copy(content = "edited")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.edit(
            original = message(),
            updated = updated,
            ownerUserId = OWNER,
            groupRevision = null,
            project = projections::add,
        )

        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Set(updated)),
            projections,
        )
        val applied = assertIs<ConversationMessageMutationOutcome.Applied>(outcome)
        val projectionError = assertIs<IllegalStateException>(applied.localProjectionError)
        assertEquals(failure.message, projectionError.message)
        assertEquals(false, applied.removePinnedReference)
    }

    @Test
    fun `revoke postcommit cancellation is a projection warning not a rollback`() = runTest {
        val coordinator = coordinator(
            persistRevoked = { _, _ -> throw CancellationException("room cancelled") },
        )
        val revoked = message().copy(content = "revoked")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.revoke(
            original = message(),
            revoked = revoked,
            ownerUserId = OWNER,
            groupRevision = null,
            project = projections::add,
        )

        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Set(revoked)),
            projections,
        )
        val applied = assertIs<ConversationMessageMutationOutcome.Applied>(outcome)
        assertIs<CancellationException>(applied.localProjectionError)
        assertEquals(true, applied.removePinnedReference)
    }

    @Test
    fun `revoke postcommit local failure keeps optimistic set and pinned reference`() = runTest {
        val failure = IllegalStateException("sqlcipher unavailable")
        val coordinator = coordinator(persistRevoked = { _, _ -> throw failure })
        val revoked = message().copy(content = "revoked")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.revoke(
            original = message(),
            revoked = revoked,
            ownerUserId = OWNER,
            groupRevision = null,
            project = projections::add,
        )

        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Set(revoked)),
            projections,
        )
        val applied = assertIs<ConversationMessageMutationOutcome.Applied>(outcome)
        val projectionError = assertIs<IllegalStateException>(applied.localProjectionError)
        assertEquals(failure.message, projectionError.message)
        assertTrue(applied.removePinnedReference)
    }

    @Test
    fun `edit postcommit cancellation is a projection warning not a rollback`() = runTest {
        val coordinator = coordinator(
            persistEdited = { throw CancellationException("room cancelled") },
        )
        val updated = message().copy(content = "edited")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.edit(
            original = message(),
            updated = updated,
            ownerUserId = OWNER,
            groupRevision = null,
            project = projections::add,
        )

        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Set(updated)),
            projections,
        )
        val applied = assertIs<ConversationMessageMutationOutcome.Applied>(outcome)
        assertIs<CancellationException>(applied.localProjectionError)
        assertEquals(false, applied.removePinnedReference)
    }

    @Test
    fun `account switch before commit rolls back optimistic projection and fails`() = runTest {
        val coordinator = coordinator()
        val original = message()
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.edit(
            original = original,
            updated = original.copy(content = "edited"),
            ownerUserId = "stale-user",
            groupRevision = null,
            project = projections::add,
        )

        assertEquals(
            listOf<MessageMutationProjection>(
                MessageMutationProjection.Set(original.copy(content = "edited")),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertEquals("messaging_v2_owner_session_changed", failed.error.message)
        assertTrue(failed.rolledBack)
    }

    @Test
    fun `revoke precommit failure rolls optimistic projection back`() = runTest {
        val failure = IllegalStateException("outbox unavailable")
        val coordinator = coordinator(enqueue = { _, _, _ -> throw failure })
        val original = message()
        val revoked = original.copy(content = "revoked")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.revoke(original, revoked, OWNER, null, projections::add)

        assertEquals(
            listOf<MessageMutationProjection>(
                MessageMutationProjection.Set(revoked),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertSame(failure, failed.error)
        assertTrue(failed.rolledBack)
    }

    @Test
    fun `edit precommit failure rolls optimistic projection back`() = runTest {
        val failure = IllegalStateException("outbox unavailable")
        val coordinator = coordinator(enqueue = { _, _, _ -> throw failure })
        val original = message()
        val updated = original.copy(content = "edited")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.edit(original, updated, OWNER, null, projections::add)

        assertEquals(
            listOf<MessageMutationProjection>(
                MessageMutationProjection.Set(updated),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertSame(failure, failed.error)
        assertTrue(failed.rolledBack)
    }

    @Test
    fun `optimistic projection failure is rethrown and tracker is released`() = runTest {
        val failure = IllegalStateException("ui project failed")
        val coordinator = coordinator()
        val original = message()

        val thrown = assertFailsWith<IllegalStateException> {
            coordinator.delete(original, OWNER, null) { throw failure }
        }
        assertSame(failure, thrown)

        val projections = mutableListOf<MessageMutationProjection>()
        val outcome = coordinator.delete(original, OWNER, null, projections::add)
        assertIs<ConversationMessageMutationOutcome.Applied>(outcome)
        assertEquals(
            listOf<MessageMutationProjection>(MessageMutationProjection.Remove(MESSAGE_ID)),
            projections,
        )
    }

    @Test
    fun `blank owner delete rolls optimistic projection back`() = runTest {
        val coordinator = coordinator()
        val original = message()
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.delete(original, "  ", null, projections::add)

        assertEquals(
            listOf(
                MessageMutationProjection.Remove(MESSAGE_ID),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertEquals("messaging_v2_owner_session_changed", failed.error.message)
        assertTrue(failed.rolledBack)
    }

    @Test
    fun `blank owner edit rolls optimistic projection back`() = runTest {
        val coordinator = coordinator()
        val original = message()
        val updated = original.copy(content = "edited")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.edit(original, updated, "  ", null, projections::add)

        assertEquals(
            listOf<MessageMutationProjection>(
                MessageMutationProjection.Set(updated),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertEquals("messaging_v2_owner_session_changed", failed.error.message)
        assertTrue(failed.rolledBack)
    }

    @Test
    fun `blank owner revoke rolls optimistic projection back`() = runTest {
        val coordinator = coordinator()
        val original = message()
        val revoked = original.copy(content = "revoked")
        val projections = mutableListOf<MessageMutationProjection>()

        val outcome = coordinator.revoke(original, revoked, "  ", null, projections::add)

        assertEquals(
            listOf<MessageMutationProjection>(
                MessageMutationProjection.Set(revoked),
                MessageMutationProjection.Set(original),
            ),
            projections,
        )
        val failed = assertIs<ConversationMessageMutationOutcome.Failed>(outcome)
        assertEquals("messaging_v2_owner_session_changed", failed.error.message)
        assertTrue(failed.rolledBack)
    }

    private fun coordinator(
        enqueue: suspend (String, MessagingV2Event, Long?) -> Unit = { _, _, _ -> },
        persistDeleted: suspend (String) -> Unit = {},
        persistEdited: suspend (Message) -> Message? = { it },
        persistRevoked: suspend (String, Message) -> Unit = { _, _ -> },
    ): ConversationMessageMutationCoordinator = ConversationMessageMutationCoordinator(
        MessagingV2MutationFacade(
            eventOutbox = MessagingV2EventOutbox(enqueue),
            persistDeleted = persistDeleted,
            persistRevoked = persistRevoked,
            persistEdited = persistEdited,
            persistReaction = { _, _, _, _ -> },
            indexMessage = {},
            cleanupAttachment = {},
            refreshConversationPreview = {},
            isOwnerSessionCurrent = { it == OWNER },
        ),
    )

    private fun message() = Message(
        id = MESSAGE_ID,
        chatId = "chat-1",
        senderId = OWNER,
        content = "body",
        type = MessageType.TEXT,
        timestamp = 1L,
    )

    private companion object {
        const val OWNER = "alice"
        const val MESSAGE_ID = "message-1"
    }
}
