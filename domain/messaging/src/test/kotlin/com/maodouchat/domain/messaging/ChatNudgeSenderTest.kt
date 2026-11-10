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

class ChatNudgeSenderTest {

    // owner 不能用 "me"：它是 PLACEHOLDER_OWNER，checkNudge 会判 NO_SESSION。
    private val me = User(id = "owner-1", name = "Me")
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
                ownerUserId = "owner-1",
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

    private fun sender(
        scope: kotlinx.coroutines.CoroutineScope,
        current: () -> ChatDetailUiState,
        updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
        staged: MutableList<Message> = mutableListOf(),
        stageError: Throwable? = null,
        previews: MutableList<Message> = mutableListOf(),
        nudgeEnabled: Boolean = true,
        activeChatId: String = "c1",
        token: String = "tok",
    ) = ChatNudgeSender(
        scope = scope,
        nudgeEnabled = { nudgeEnabled },
        activeChatId = { activeChatId },
        chatId = { "c1" },
        ownerUserId = { "owner-1" },
        token = { token },
        currentState = current,
        updateState = updateState,
        mergeMessages = { a, b -> a + b },
        emitListPreviewForDecrypted = { previews += it },
        outgoingFacade = fakeFacade(staged, stageError),
        text = { message, _ -> message.name },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun state(
        isSecretChat: Boolean? = false,
        contact: User = peer,
        messages: List<Message> = emptyList(),
    ) = ChatDetailUiState(
        contact = contact,
        chat = chat,
        isSecretChat = isSecretChat,
        messages = messages,
    )

    @Test
    fun secretChatRejectsWithWarning() = runTest {
        var current = state(isSecretChat = true)
        sender(this, { current }, { transform -> current = transform(current) }).sendNudge()
        assertEquals("SECRET_CHAT_FORWARD_BLOCKED", current.groupEncryptionWarning)
        assertNull(current.errorMessage)
    }

    @Test
    fun disabledNudgeRejectsWithError() = runTest {
        var current = state()
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            nudgeEnabled = false,
        ).sendNudge()
        assertEquals("NUDGE_DISABLED", current.errorMessage)
        assertNull(current.groupEncryptionWarning)
    }

    @Test
    fun noChatRejectsWithWarning() = runTest {
        var current = state()
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            activeChatId = "",
        ).sendNudge()
        assertEquals("CHAT_WS_SEND_FAILED", current.groupEncryptionWarning)
    }

    @Test
    fun noSessionRejectsWithWarning() = runTest {
        var current = state()
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            token = "",
        ).sendNudge()
        assertEquals("ERROR_SESSION_EXPIRED", current.groupEncryptionWarning)
    }

    @Test
    fun successStagesNudgeAndEmitsPreview() = runTest {
        var current = state()
        val staged = mutableListOf<Message>()
        val previews = mutableListOf<Message>()
        sender(this, { current }, { transform -> current = transform(current) }, staged, previews = previews).sendNudge()
        advanceUntilIdle()
        assertEquals(1, current.messages.size)
        assertEquals(MessageType.NUDGE, current.messages[0].type)
        assertEquals(MessageStatus.SENDING, current.messages[0].status)
        assertEquals("CHAT_NUDGE_YOU_NUDGED", current.messages[0].content)
        assertNull(current.groupEncryptionWarning)
        assertEquals(1, staged.size)
        assertEquals(1, previews.size)
        assertEquals(staged[0].id, previews[0].id)
    }

    @Test
    fun facadeFailureMarksFailedWithErrorText() = runTest {
        var current = state()
        sender(
            this,
            { current },
            { transform -> current = transform(current) },
            stageError = RuntimeException("boom"),
        ).sendNudge()
        advanceUntilIdle()
        assertEquals(1, current.messages.size)
        assertEquals(MessageStatus.FAILED, current.messages[0].status)
        assertEquals("boom", current.groupEncryptionWarning)
    }

    @Test
    fun blankContactNameFallsBackToOtherPerson() = runTest {
        var current = state(contact = User(id = "u2", name = ""))
        val seen = mutableListOf<String>()
        val s = ChatNudgeSender(
            scope = this,
            nudgeEnabled = { true },
            activeChatId = { "c1" },
            chatId = { "c1" },
            ownerUserId = { "owner-1" },
            token = { "tok" },
            currentState = { current },
            updateState = { transform -> current = transform(current) },
            mergeMessages = { a, b -> a + b },
            emitListPreviewForDecrypted = {},
            outgoingFacade = fakeFacade(),
            text = { message, args ->
                if (args.isNotEmpty()) seen += args[0].toString()
                message.name
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        s.sendNudge()
        advanceUntilIdle()
        assertEquals(listOf("CHAT_OTHER_PERSON"), seen)
        assertTrue(current.messages.isNotEmpty())
    }
}
