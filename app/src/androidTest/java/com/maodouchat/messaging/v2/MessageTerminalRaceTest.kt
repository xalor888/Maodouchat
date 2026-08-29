package com.maodouchat.messaging.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2InboxState
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageTerminalRaceTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun terminalEventConflictRollsBackItsTombstone() = runBlocking {
        insertConversation()
        val outbox = outbox()
        outbox.enqueueEvent(
            conversationId = CHAT_ID,
            event = MessagingV2Event(
                action = MessagingV2EventAction.REACTION_SET,
                targetMessageId = TARGET_MESSAGE_ID,
                reactionEmoji = "heart",
            ),
            messageId = COMMAND_ID,
        )

        runCatching {
            outbox.enqueueEvent(
                conversationId = CHAT_ID,
                event = MessagingV2Event(
                    action = MessagingV2EventAction.DELETE,
                    targetMessageId = TARGET_MESSAGE_ID,
                ),
                messageId = COMMAND_ID,
            )
        }.onSuccess { error("expected outbox id conflict") }

        assertFalse(database.messagingV2Dao().isMessageTerminal(OWNER, TARGET_MESSAGE_ID))
    }

    @Test
    fun terminalTombstoneRejectsLateDataStageWithoutRecreatingMessage() = runBlocking {
        insertConversation()
        val outbox = outbox()
        outbox.enqueueEvent(
            conversationId = CHAT_ID,
            event = MessagingV2Event(
                action = MessagingV2EventAction.DELETE,
                targetMessageId = TARGET_MESSAGE_ID,
            ),
            messageId = COMMAND_ID,
        )
        assertTrue(database.messagingV2Dao().isMessageTerminal(OWNER, TARGET_MESSAGE_ID))

        val staged = MessagingV2MessageGateway(
            database = database,
            messageStore = LocalMessageStore(database.messageDao(), database),
            outbox = outbox,
        ).stageAndEnqueue(
            Message(
                id = TARGET_MESSAGE_ID,
                chatId = CHAT_ID,
                senderId = OWNER,
                content = "late attachment reference",
                type = MessageType.FILE,
                timestamp = 10L,
                status = MessageStatus.SENDING,
            ),
        )

        assertFalse(staged)
        assertNull(database.messageDao().getMessageById(TARGET_MESSAGE_ID))
        assertNull(database.messagingV2Dao().getOutbox(TARGET_MESSAGE_ID, OWNER))
        assertNotNull(database.messagingV2Dao().getOutbox(COMMAND_ID, OWNER))
    }

    @Test
    fun failedEarlierInboxEnvelopeBlocksLaterReadyEnvelopeUntilRetry() = runBlocking {
        val dao = database.messagingV2Dao()
        dao.insertInbox(
            listOf(
                inbox("first", sequence = 1L, state = MessagingV2InboxState.FAILED, nextAttemptAt = 10_001L),
                inbox("second", sequence = 2L, state = MessagingV2InboxState.RECEIVED),
            ),
        )

        assertNull(dao.nextProcessableInbox(OWNER, 1, now = 10_000L))
        assertTrue(dao.claimNextInbox(OWNER, 1, now = 10_001L)?.envelopeId == "first")
    }

    @Test
    fun processClaimOnEarlierInboxEnvelopeBlocksLaterReadyEnvelope() = runBlocking {
        val dao = database.messagingV2Dao()
        dao.insertInbox(
            listOf(
                inbox("first", sequence = 1L, state = MessagingV2InboxState.PROCESSING),
                inbox("second", sequence = 2L, state = MessagingV2InboxState.RECEIVED),
            ),
        )

        assertNull(dao.nextProcessableInbox(OWNER, 1, now = 10_000L))
    }

    @Test
    fun everyGroupControlPreemptsOlderDataFromAnotherConversation() = runBlocking {
        val dao = database.messagingV2Dao()
        listOf("SENDER_KEY", "KEY_REQUEST", "RECEIPT").forEachIndexed { index, kind ->
            val dataId = "data-$kind"
            val controlId = "control-$kind"
            dao.enqueueOutbox(outbox(dataId, conversationId = "chat-data-$index", kind = "DATA"))
            dao.enqueueOutbox(outbox(controlId, conversationId = "chat-control-$index", kind = kind))

            assertTrue(dao.nextProcessableOutbox(OWNER, now = 10_000L)?.messageId == controlId)
            dao.discardOutbox(controlId, OWNER, MessagingV2OutboxState.QUEUED)
            dao.discardOutbox(dataId, OWNER, MessagingV2OutboxState.QUEUED)
        }
    }

    private fun inbox(
        id: String,
        sequence: Long,
        state: String,
        nextAttemptAt: Long = 0L,
    ) = MessagingV2InboxEntity(
        envelopeId = id,
        ownerUserId = OWNER,
        deviceId = 1,
        sequence = sequence,
        messageId = "message-$id",
        conversationId = CHAT_ID,
        senderUserId = "bob",
        senderDeviceId = 2,
        kind = "DATA",
        clientTimestamp = sequence,
        serverTimestamp = sequence,
        ciphertextType = "SIGNAL",
        ciphertext = "ciphertext",
        state = state,
        nextAttemptAt = nextAttemptAt,
    )

    private fun outbox(messageId: String, conversationId: String, kind: String) = MessagingV2OutboxEntity(
        messageId = messageId,
        ownerUserId = OWNER,
        conversationId = conversationId,
        kind = kind,
        localPayload = "{}",
        clientTimestamp = 1L,
        state = MessagingV2OutboxState.QUEUED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun insertConversation() {
        database.chatDao().insertChats(listOf(ChatEntity(id = CHAT_ID, participantIds = OWNER)))
    }

    private fun outbox() = MessagingV2Outbox(
        database = database,
        dao = database.messagingV2Dao(),
        ownerUserId = { OWNER },
        deviceId = { 1 },
        wakeTransport = {},
        clock = { 100L },
    )

    private companion object {
        const val OWNER = "alice"
        const val CHAT_ID = "chat-1"
        const val TARGET_MESSAGE_ID = "message-1"
        const val COMMAND_ID = "command-1"
    }
}
