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

    private fun coordinator(
        enqueue: suspend (String, MessagingV2Event, Long?) -> Unit = { _, _, _ -> },
        persistDeleted: suspend (String) -> Unit = {},
    ): ConversationMessageMutationCoordinator = ConversationMessageMutationCoordinator(
        MessagingV2MutationFacade(
            eventOutbox = MessagingV2EventOutbox(enqueue),
            persistDeleted = persistDeleted,
            persistRevoked = { _, _ -> },
            persistEdited = { it },
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
