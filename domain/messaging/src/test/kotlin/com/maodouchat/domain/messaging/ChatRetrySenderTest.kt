package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.OutgoingConversationErrors
import com.maodouchat.core.model.OutgoingConversationRequest
import com.maodouchat.core.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRetrySenderTest {

    private val me = User(id = "me", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    private fun fakeFacade(
        staged: MutableList<Message> = mutableListOf(),
        stageError: Throwable? = null,
    ) = ChatOutgoingFacade(
        getCachedConversation = { chat },
        fetchConversations = { listOf(chat) },
        createDirectConversation = { _, _, _ -> chat },
        cacheConversation = {},
        ensureLocalCryptoReady = { _, _ -> true },
        isBotUserId = { false },
        isOwnerSessionCurrent = { true },
        errors = OutgoingConversationErrors(
            notLoggedIn = { "not logged in" },
            recipientNotReady = { "not ready" },
            cannotSendToSelf = { "self" },
        ),
        currentRequest = {
            OutgoingConversationRequest(
                ownerUserId = "me",
                authToken = "tok",
                activeConversationId = "c1",
                constructorConversationId = "",
                paintedConversation = null,
                activeContactId = "u2",
                createSecretConversation = false,
            )
        },
        hydrateConversation = { resolvedChat, _, peerId -> ResolvedOutgoingChat(resolvedChat.id, resolvedChat, peerId) },
        stageDurableMessage = { message, _, _, _ ->
            if (stageError != null) throw stageError
            staged += message
        },
        retryDurableMessage = { message, _, _, _ ->
            if (stageError != null) throw stageError
            staged += message
        },
        persistFailedMessage = {},
    )

    private fun failedMsg(
        id: String = "m1",
        type: MessageType = MessageType.TEXT,
        senderId: String = "me",
        status: MessageStatus = MessageStatus.FAILED,
        content: String = "hi",
    ) = Message(id = id, chatId = "c1", senderId = senderId, content = content, type = type, status = status)

    private fun sender(
        scope: kotlinx.coroutines.CoroutineScope,
        current: () -> ChatDetailUiState,
        updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
        staged: MutableList<Message> = mutableListOf(),
        stageError: Throwable? = null,
        attachments: MutableList<Triple<String, MessageType, String>> = mutableListOf(),
        warnings: MutableList<String> = mutableListOf(),
        persisted: MutableList<Message> = mutableListOf(),
        token: String = "tok",
    ) = ChatRetrySender(
        scope = scope,
        ownerUserId = { "me" },
        token = { token },
        currentState = current,
        updateState = updateState,
        sessionActive = { true },
        outgoingFacade = fakeFacade(staged, stageError),
        persistMessage = { persisted += it },
        sendAttachmentRetry = { uriString, type, messageId, _ -> attachments += Triple(uriString, type, messageId) },
        text = { message, _ -> message.name },
        ioDispatcher = Dispatchers.Unconfined,
        logWarning = { message, _ -> warnings += message },
    )

    @Test
    fun attachmentTypeDelegatesToAttachmentRetry() = runTest {
        var current = ChatDetailUiState(
            chat = chat,
            messages = listOf(failedMsg(type = MessageType.IMAGE, content = "https://x/y.jpg")),
        )
        val attachments = mutableListOf<Triple<String, MessageType, String>>()
        sender(this, { current }, { transform -> current = transform(current) }, attachments = attachments)
            .retrySendMessage("m1")
        advanceUntilIdle()
        assertEquals(listOf(Triple("https://x/y.jpg", MessageType.IMAGE, "m1")), attachments)
        assertEquals(MessageStatus.FAILED, current.messages[0].status)
    }

    @Test
    fun noSessionRejectsWithWarning() = runTest {
        var current = ChatDetailUiState(chat = chat, messages = listOf(failedMsg()))
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            token = "",
        ).retrySendMessage("m1")
        assertEquals("ERROR_SESSION_EXPIRED", current.groupEncryptionWarning)
        assertEquals(MessageStatus.FAILED, current.messages[0].status)
    }

    @Test
    fun missingMessageIsNoop() = runTest {
        var current = ChatDetailUiState(chat = chat, messages = listOf(failedMsg()))
        val staged = mutableListOf<Message>()
        sender(this, { current }, { transform -> current = transform(current) }, staged = staged)
            .retrySendMessage("nope")
        advanceUntilIdle()
        assertTrue(staged.isEmpty())
        assertEquals(MessageStatus.FAILED, current.messages[0].status)
    }

    @Test
    fun othersMessageIsNoop() = runTest {
        var current = ChatDetailUiState(chat = chat, messages = listOf(failedMsg(senderId = "u2")))
        val staged = mutableListOf<Message>()
        sender(this, { current }, { transform -> current = transform(current) }, staged = staged)
            .retrySendMessage("m1")
        advanceUntilIdle()
        assertTrue(staged.isEmpty())
    }

    @Test
    fun nonFailedMessageIsNoop() = runTest {
        var current = ChatDetailUiState(chat = chat, messages = listOf(failedMsg(status = MessageStatus.SENT)))
        val staged = mutableListOf<Message>()
        sender(this, { current }, { transform -> current = transform(current) }, staged = staged)
            .retrySendMessage("m1")
        advanceUntilIdle()
        assertTrue(staged.isEmpty())
    }

    @Test
    fun textRetryStagesAndKeepsSending() = runTest {
        var current = ChatDetailUiState(chat = chat, messages = listOf(failedMsg()))
        val staged = mutableListOf<Message>()
        val warnings = mutableListOf<String>()
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            staged = staged,
            warnings = warnings,
        ).retrySendMessage("m1")
        assertEquals(MessageStatus.SENDING, current.messages[0].status)
        advanceUntilIdle()
        assertEquals(MessageStatus.SENDING, current.messages[0].status)
        assertEquals("c1", current.messages[0].chatId)
        assertEquals(1, staged.size)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun facadeFailureMarksFailedWarnsAndKeepsText() = runTest {
        var current = ChatDetailUiState(chat = chat, messages = listOf(failedMsg()))
        val warnings = mutableListOf<String>()
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            stageError = RuntimeException("boom"),
            warnings = warnings,
        ).retrySendMessage("m1")
        advanceUntilIdle()
        assertEquals(MessageStatus.FAILED, current.messages[0].status)
        // 重试失败只记 log，不弹 UI 警告（与生产实现一致）。
        assertNull(current.groupEncryptionWarning)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].startsWith("v2 retry enqueue failed: boom"))
    }
}
