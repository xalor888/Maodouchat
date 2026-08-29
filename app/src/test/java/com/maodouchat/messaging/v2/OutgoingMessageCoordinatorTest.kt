package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutgoingMessageCoordinatorTest {
    @Test
    fun `message is rebound to resolved conversation before durable staging`() = runTest {
        val staged = mutableListOf<Message>()
        val coordinator = coordinator(
            stage = { message, revision, body, type ->
                assertEquals(9L, revision)
                assertEquals("wire body", body)
                assertEquals(MessageType.TEXT, type)
                staged += message
            },
        )

        val result = coordinator.enqueue(command())

        val success = assertIs<OutgoingMessageResult.Staged>(result)
        assertEquals("c_resolved", success.message.chatId)
        assertEquals(listOf("c_resolved"), staged.map(Message::chatId))
    }

    @Test
    fun `stage failure persists failed projection and reports precommit failure`() = runTest {
        val persisted = mutableListOf<Message>()
        var failureCallbackCount = 0
        val coordinator = coordinator(
            stage = { _, _, _, _ -> error("outbox unavailable") },
            persistFailed = persisted::add,
        )

        val result = coordinator.enqueue(
            command(),
            onDurableFailure = { failureCallbackCount += 1 },
        )

        val failed = assertIs<OutgoingMessageResult.Failed>(result)
        assertEquals(MessageStatus.FAILED, failed.message.status)
        assertEquals("c_resolved", failed.message.chatId)
        assertEquals(false, failed.durableCommitCompleted)
        assertEquals(1, failureCallbackCount)
        assertEquals(listOf(MessageStatus.FAILED), persisted.map(Message::status))
    }

    @Test
    fun `session switch after durable commit cancels without marking message failed`() = runTest {
        val persisted = mutableListOf<Message>()
        var committed = false
        var failed = false
        val coordinator = coordinator(
            sessionCurrent = false,
            persistFailed = persisted::add,
        )

        assertFailsWith<CancellationException> {
            coordinator.enqueue(
                command(),
                onDurableCommit = { committed = true },
                onDurableFailure = { failed = true },
            )
        }

        assertTrue(committed)
        assertEquals(false, failed)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `retry uses retry operation and keeps the original message id`() = runTest {
        var staged = 0
        val retried = mutableListOf<Message>()
        val coordinator = OutgoingMessageCoordinator(
            resolveConversation = {
                OutgoingConversationContext("c_retry", 4L, isGroup = true, peerUserId = null)
            },
            stageDurableMessage = { _, _, _, _ -> staged += 1 },
            retryDurableMessage = { message, revision, _, _ ->
                assertEquals(4L, revision)
                retried += message
            },
            persistFailedMessage = {},
            isOwnerSessionCurrent = { true },
        )

        val result = coordinator.retry(command())

        assertIs<OutgoingMessageResult.Staged>(result)
        assertEquals(0, staged)
        assertEquals(listOf("m1"), retried.map(Message::id))
        assertEquals(listOf("c_retry"), retried.map(Message::chatId))
    }

    private fun coordinator(
        stage: suspend (Message, Long?, String, MessageType) -> Unit = { _, _, _, _ -> },
        persistFailed: suspend (Message) -> Unit = {},
        sessionCurrent: Boolean = true,
    ) = OutgoingMessageCoordinator(
        resolveConversation = {
            OutgoingConversationContext(
                conversationId = "c_resolved",
                groupRevision = 9L,
                isGroup = true,
                peerUserId = null,
            )
        },
        stageDurableMessage = stage,
        persistFailedMessage = persistFailed,
        isOwnerSessionCurrent = { sessionCurrent },
    )

    private fun command() = OutgoingMessageCommand(
        ownerUserId = "u1",
        optimisticMessage = Message(
            id = "m1",
            chatId = "temporary",
            senderId = "u1",
            content = "wire body",
            type = MessageType.TEXT,
            timestamp = 1L,
            status = MessageStatus.SENDING,
        ),
        body = "wire body",
        type = MessageType.TEXT,
    )
}
