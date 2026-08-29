package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessagingV2MutationFacadeTest {
    @Test
    fun `edit is durable in outbox before local projection changes`() = runTest {
        val effects = mutableListOf<String>()
        val events = mutableListOf<MessagingV2Event>()
        val facade = facade(
            enqueue = { _, event, _ ->
                effects += "outbox"
                events += event
            },
            persistMessage = { effects += "room" },
            indexMessage = { effects += "index" },
            refreshPreview = { effects += "preview" },
        )
        val updated = message(content = "edited", editedAt = 42L)

        facade.edit(updated, OWNER, groupRevision = 7L)

        assertEquals(listOf("outbox", "room", "index", "preview"), effects)
        assertEquals(
            MessagingV2Event(
                action = MessagingV2EventAction.EDIT,
                targetMessageId = MESSAGE_ID,
                content = "edited",
                editedAt = 42L,
            ),
            events.single(),
        )
    }

    @Test
    fun `changed owner session rejects mutation before any side effect`() = runTest {
        val effects = mutableListOf<String>()
        val facade = facade(
            enqueue = { _, _, _ -> effects += "outbox" },
            persistMessage = { effects += "room" },
            ownerIsCurrent = { false },
        )

        assertFailsWith<IllegalArgumentException> {
            facade.edit(message(), OWNER, groupRevision = null)
        }
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `reaction event carries only actor choice while room keeps merged reactions`() = runTest {
        var event: MessagingV2Event? = null
        var persisted: Message? = null
        val reactions = listOf(
            MessageReaction("alice", "heart", 10L),
            MessageReaction("bob", "laugh", 20L),
        )
        val facade = facade(
            enqueue = { _, value, _ -> event = value },
            persistMessage = { persisted = it },
        )

        facade.setReaction(
            original = message(),
            reactions = reactions,
            reactionEmoji = "heart",
            ownerUserId = OWNER,
            groupRevision = null,
        )

        assertEquals(MessagingV2EventAction.REACTION_SET, event?.action)
        assertEquals("heart", event?.reactionEmoji)
        assertTrue(event?.reactions.orEmpty().isEmpty())
        assertNull(event?.content)
        assertEquals(reactions, persisted?.reactions)
    }

    @Test
    fun `local projection failure after durable event is reported without undoing commit`() = runTest {
        val effects = mutableListOf<String>()
        val diskFailure = IllegalStateException("sqlcipher unavailable")
        val facade = facade(
            enqueue = { _, _, _ -> effects += "outbox" },
            persistMessage = {
                effects += "room"
                throw diskFailure
            },
        )

        val commit = facade.edit(message(content = "edited"), OWNER, groupRevision = null)

        assertEquals(listOf("outbox", "room"), effects)
        val projectionError = assertIs<IllegalStateException>(commit.localProjectionError)
        assertEquals(diskFailure.message, projectionError.message)
    }

    @Test
    fun `postcommit cancellation is a projection warning not a precommit rollback signal`() = runTest {
        val facade = facade(
            persistMessage = { throw CancellationException("room cancelled") },
        )

        val commit = facade.setReaction(
            original = message(),
            reactions = emptyList(),
            reactionEmoji = null,
            ownerUserId = OWNER,
            groupRevision = null,
        )

        assertIs<CancellationException>(commit.localProjectionError)
    }

    @Test
    fun `rejected stale edit is not written to search index`() = runTest {
        val effects = mutableListOf<String>()
        val facade = facade(
            persistEdited = {
                effects += "room-rejected"
                null
            },
            indexMessage = { effects += "index" },
        )

        facade.edit(message(content = "stale", editedAt = 5L), OWNER, groupRevision = null)

        assertEquals(listOf("room-rejected"), effects)
    }

    @Test
    fun `terminal cleanup continues after an earlier local projection failure`() = runTest {
        val effects = mutableListOf<String>()
        val facade = MessagingV2MutationFacade(
            eventOutbox = MessagingV2EventOutbox { _, _, _ -> effects += "outbox" },
            persistDeleted = {
                effects += "room"
                throw IllegalStateException("room failed")
            },
            persistRevoked = { _, _ -> },
            persistEdited = { it },
            persistReaction = { _, _, _, _ -> },
            indexMessage = {},
            cleanupAttachment = { effects += "attachment" },
            refreshConversationPreview = { effects += "preview" },
            isOwnerSessionCurrent = { true },
            cleanupTerminalNotification = { effects += "notification" },
        )

        val commit = facade.delete(message(), OWNER, groupRevision = null)

        assertEquals(listOf("outbox", "room", "attachment", "notification", "preview"), effects)
        assertIs<IllegalStateException>(commit.localProjectionError)
    }

    private fun facade(
        enqueue: suspend (String, MessagingV2Event, Long?) -> Unit = { _, _, _ -> },
        persistMessage: suspend (Message) -> Unit = {},
        persistEdited: (suspend (Message) -> Message?)? = null,
        indexMessage: suspend (Message) -> Unit = {},
        refreshPreview: (String) -> Unit = {},
        ownerIsCurrent: (String) -> Boolean = { true },
    ) = MessagingV2MutationFacade(
        eventOutbox = MessagingV2EventOutbox(enqueue),
        persistDeleted = {},
        persistRevoked = { _, _ -> },
        persistEdited = persistEdited ?: { message ->
            persistMessage(message)
            message
        },
        persistReaction = { original, reactions, _, _ ->
            persistMessage(original.copy(reactions = reactions))
        },
        indexMessage = indexMessage,
        cleanupAttachment = {},
        refreshConversationPreview = refreshPreview,
        isOwnerSessionCurrent = ownerIsCurrent,
    )

    private fun message(
        content: String = "body",
        editedAt: Long? = null,
    ) = Message(
        id = MESSAGE_ID,
        chatId = "chat-1",
        senderId = OWNER,
        content = content,
        type = MessageType.TEXT,
        timestamp = 1L,
        editedAt = editedAt,
    )

    private companion object {
        const val OWNER = "alice"
        const val MESSAGE_ID = "message-1"
    }
}
