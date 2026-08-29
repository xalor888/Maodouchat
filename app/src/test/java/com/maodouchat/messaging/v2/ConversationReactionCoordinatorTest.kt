package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationReactionCoordinatorTest {
    @Test
    fun `same emoji toggles only current actor reaction off`() = runTest {
        var current = message(
            reactions = listOf(
                MessageReaction(OWNER, "heart", 1L),
                MessageReaction("bob", "laugh", 2L),
            ),
        )
        var event: MessagingV2Event? = null
        val coordinator = coordinator(
            enqueue = { _, value, _ -> event = value },
        )

        val outcome = coordinator.toggle(
            messageId = MESSAGE_ID,
            emoji = "heart",
            ownerUserId = OWNER,
            groupRevision = { null },
            currentMessage = { current },
            project = { current = it },
        )

        assertIs<ConversationReactionOutcome.Applied>(outcome)
        assertEquals(listOf(MessageReaction("bob", "laugh", 2L)), current.reactions)
        assertEquals(null, event?.reactionEmoji)
    }

    @Test
    fun `precommit failure restores original reactions`() = runTest {
        val original = message(reactions = listOf(MessageReaction("bob", "laugh", 2L)))
        var current = original
        val failure = IllegalStateException("outbox unavailable")
        val coordinator = coordinator(enqueue = { _, _, _ -> throw failure })

        val outcome = coordinator.toggle(
            MESSAGE_ID,
            "heart",
            OWNER,
            { null },
            currentMessage = { current },
            project = { current = it },
        )

        assertIs<ConversationReactionOutcome.Failed>(outcome)
        assertEquals(original, current)
    }

    @Test
    fun `postcommit projection failure keeps optimistic reactions`() = runTest {
        var current = message()
        val diskFailure = IllegalStateException("sqlcipher unavailable")
        val coordinator = coordinator(persistMessage = { throw diskFailure })

        val outcome = coordinator.toggle(
            MESSAGE_ID,
            "heart",
            OWNER,
            { null },
            currentMessage = { current },
            project = { current = it },
        )

        val applied = assertIs<ConversationReactionOutcome.Applied>(outcome)
        assertIs<IllegalStateException>(applied.localProjectionError)
        assertEquals("heart", current.reactions.single().emoji)
    }

    @Test
    fun `precommit cancellation restores original and rethrows`() = runTest {
        val original = message()
        var current = original
        val coordinator = coordinator(
            enqueue = { _, _, _ -> throw CancellationException("cancel before commit") },
        )

        assertFailsWith<CancellationException> {
            coordinator.toggle(
                MESSAGE_ID,
                "heart",
                OWNER,
                { null },
                currentMessage = { current },
                project = { current = it },
            )
        }

        assertEquals(original, current)
    }

    @Test
    fun `concurrent toggles serialize against latest projected message`() = runTest {
        val firstEnteredOutbox = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var enqueueCount = 0
        var current = message()
        val coordinator = coordinator(
            enqueue = { _, _, _ ->
                enqueueCount++
                if (enqueueCount == 1) {
                    firstEnteredOutbox.complete(Unit)
                    releaseFirst.await()
                }
            },
        )

        val first = async {
            coordinator.toggle(
                MESSAGE_ID,
                "heart",
                OWNER,
                { null },
                currentMessage = { current },
                project = { current = it },
            )
        }
        firstEnteredOutbox.await()
        val second = async {
            coordinator.toggle(
                MESSAGE_ID,
                "laugh",
                OWNER,
                { null },
                currentMessage = { current },
                project = { current = it },
            )
        }
        releaseFirst.complete(Unit)

        assertIs<ConversationReactionOutcome.Applied>(first.await())
        assertIs<ConversationReactionOutcome.Applied>(second.await())
        assertEquals("laugh", current.reactions.single().emoji)
        assertEquals(2, enqueueCount)
    }

    @Test
    fun `revoked message is ignored without projection`() = runTest {
        val current = message().copy(type = MessageType.REVOKED)
        var projected = false
        val coordinator = coordinator()

        val outcome = coordinator.toggle(
            MESSAGE_ID,
            "heart",
            OWNER,
            { null },
            currentMessage = { current },
            project = { projected = true },
        )

        assertIs<ConversationReactionOutcome.Ignored>(outcome)
        assertTrue(!projected)
    }

    @Test
    fun `failed toggle preserves concurrent remote reaction update`() = runTest {
        val original = message(
            reactions = listOf(MessageReaction("bob", "laugh", 2L)),
        )
        var current = original
        val coordinator = coordinator(
            enqueue = { _, _, _ ->
                current = current.copy(
                    reactions = current.reactions.filterNot { it.userId == "bob" } +
                        MessageReaction("bob", "wow", 9L),
                )
                throw IllegalStateException("outbox unavailable")
            },
        )

        coordinator.toggle(
            MESSAGE_ID,
            "heart",
            OWNER,
            { null },
            currentMessage = { current },
            project = { current = it },
        )

        assertEquals(listOf(MessageReaction("bob", "wow", 9L)), current.reactions)
    }

    @Test
    fun `failed toggle does not resurrect concurrently revoked message`() = runTest {
        var current = message()
        val coordinator = coordinator(
            enqueue = { _, _, _ ->
                current = current.copy(type = MessageType.REVOKED, reactions = emptyList())
                throw IllegalStateException("outbox unavailable")
            },
        )

        coordinator.toggle(
            MESSAGE_ID,
            "heart",
            OWNER,
            { null },
            currentMessage = { current },
            project = { current = it },
        )

        assertEquals(MessageType.REVOKED, current.type)
        assertTrue(current.reactions.isEmpty())
    }

    @Test
    fun `group revision is read at durable enqueue time`() = runTest {
        var revision = 7L
        var capturedRevision: Long? = null
        var current = message()
        val coordinator = coordinator(
            enqueue = { _, _, value -> capturedRevision = value },
        )
        revision = 8L

        coordinator.toggle(
            MESSAGE_ID,
            "heart",
            OWNER,
            { revision },
            currentMessage = { current },
            project = { current = it },
        )

        assertEquals(8L, capturedRevision)
    }

    private fun coordinator(
        enqueue: suspend (String, MessagingV2Event, Long?) -> Unit = { _, _, _ -> },
        persistMessage: suspend (Message) -> Unit = {},
    ) = ConversationReactionCoordinator(
        facade = MessagingV2MutationFacade(
            eventOutbox = MessagingV2EventOutbox(enqueue),
            persistDeleted = {},
            persistRevoked = { _, _ -> },
            persistEdited = { message ->
                persistMessage(message)
                message
            },
            persistReaction = { original, reactions, _, _ ->
                persistMessage(original.copy(reactions = reactions))
            },
            indexMessage = {},
            cleanupAttachment = {},
            refreshConversationPreview = {},
            isOwnerSessionCurrent = { it == OWNER },
        ),
        clock = { 42L },
    )

    private fun message(
        reactions: List<MessageReaction> = emptyList(),
    ) = Message(
        id = MESSAGE_ID,
        chatId = "chat-1",
        senderId = OWNER,
        content = "body",
        type = MessageType.TEXT,
        timestamp = 1L,
        reactions = reactions,
    )

    private companion object {
        const val OWNER = "alice"
        const val MESSAGE_ID = "message-1"
    }
}
