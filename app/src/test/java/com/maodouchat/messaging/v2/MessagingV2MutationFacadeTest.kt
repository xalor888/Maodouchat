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

    @Test
    fun `revoke is durable before local projection and carries revoked content`() = runTest {
        val effects = mutableListOf<String>()
        var event: MessagingV2Event? = null
        var revoked: Message? = null
        val facade = facade(
            enqueue = { _, value, _ ->
                effects += "outbox"
                event = value
            },
            persistRevoked = { _, value ->
                effects += "room"
                revoked = value
            },
            cleanupAttachment = { effects += "attachment" },
            cleanupTerminalNotification = { effects += "notification" },
            refreshPreview = { effects += "preview" },
        )

        val commit = facade.revoke(
            original = message(content = "original"),
            revoked = message(content = "revoked", editedAt = 99L),
            ownerUserId = OWNER,
            groupRevision = 5L,
        )

        assertEquals(listOf("outbox", "room", "attachment", "notification", "preview"), effects)
        assertEquals(MessagingV2EventAction.REVOKE, event?.action)
        assertEquals(MESSAGE_ID, event?.targetMessageId)
        assertEquals("revoked", event?.content)
        assertEquals(99L, event?.editedAt)
        assertEquals("revoked", revoked?.content)
        assertNull(commit.localProjectionError)
    }

    @Test
    fun `revoke editedAt falls back when revoked message has none`() = runTest {
        var event: MessagingV2Event? = null
        val facade = facade(enqueue = { _, value, _ -> event = value })
        val before = System.currentTimeMillis()

        facade.revoke(message(), message(editedAt = null), OWNER, groupRevision = null)

        val stamped = event?.editedAt
        assertTrue(stamped != null && stamped >= before)
    }

    @Test
    fun `revoke with mismatched message ids is rejected before any side effect`() = runTest {
        val effects = mutableListOf<String>()
        val facade = facade(
            enqueue = { _, _, _ -> effects += "outbox" },
            persistRevoked = { _, _ -> effects += "room" },
            cleanupAttachment = { effects += "attachment" },
            refreshPreview = { effects += "preview" },
        )
        val other = message().copy(id = "message-2")

        assertFailsWith<IllegalArgumentException> {
            facade.revoke(message(), other, OWNER, groupRevision = null)
        }
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `changed owner session rejects delete before any side effect`() = runTest {
        val effects = mutableListOf<String>()
        val facade = facade(
            enqueue = { _, _, _ -> effects += "outbox" },
            persistDeleted = { effects += "room" },
            cleanupAttachment = { effects += "attachment" },
            cleanupTerminalNotification = { effects += "notification" },
            refreshPreview = { effects += "preview" },
            ownerIsCurrent = { false },
        )

        assertFailsWith<IllegalArgumentException> {
            facade.delete(message(), OWNER, groupRevision = null)
        }
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `edit without search index and preview refresh stays silent`() = runTest {
        val effects = mutableListOf<String>()
        val facade = facade(
            enqueue = { _, _, _ -> effects += "outbox" },
            persistMessage = { effects += "room" },
            indexMessage = { effects += "index" },
            refreshPreview = { effects += "preview" },
        )

        facade.edit(
            message(content = "edited"),
            OWNER,
            groupRevision = null,
            indexForSearch = false,
            refreshPreview = false,
        )

        assertEquals(listOf("outbox", "room"), effects)
    }

    private fun facade(
        enqueue: suspend (String, MessagingV2Event, Long?) -> Unit = { _, _, _ -> },
        persistMessage: suspend (Message) -> Unit = {},
        persistDeleted: suspend (String) -> Unit = {},
        persistRevoked: suspend (String, Message) -> Unit = { _, _ -> },
        persistEdited: (suspend (Message) -> Message?)? = null,
        indexMessage: suspend (Message) -> Unit = {},
        refreshPreview: (String) -> Unit = {},
        ownerIsCurrent: (String) -> Boolean = { true },
        cleanupAttachment: suspend (String) -> Unit = {},
        cleanupTerminalNotification: suspend (Message) -> Unit = {},
    ) = MessagingV2MutationFacade(
        eventOutbox = MessagingV2EventOutbox(enqueue),
        persistDeleted = persistDeleted,
        persistRevoked = persistRevoked,
        persistEdited = persistEdited ?: { message ->
            persistMessage(message)
            message
        },
        persistReaction = { original, reactions, _, _ ->
            persistMessage(original.copy(reactions = reactions))
        },
        indexMessage = indexMessage,
        cleanupAttachment = cleanupAttachment,
        refreshConversationPreview = refreshPreview,
        isOwnerSessionCurrent = ownerIsCurrent,
        cleanupTerminalNotification = cleanupTerminalNotification,
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
