package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.OutgoingConversationErrors
import com.maodouchat.core.model.OutgoingConversationRequest
import com.maodouchat.core.model.User
import com.maodouchat.core.model.mergeMessageVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposerSendControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    // res id 替身：domain 测试看不到 app R。
    private val inFlightRes = 1001
    private val emptyRes = 1002
    private val expiredRes = 1003
    private val blockedRes = 1004
    private val mentionRes = 1005
    private val failedRes = 1006

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

    private class Harness(
        var current: ChatDetailUiState,
        val staged: MutableList<Message> = mutableListOf(),
        val commits: MutableList<Unit> = mutableListOf(),
        val failures: MutableList<Unit> = mutableListOf(),
        val botInbox: MutableList<List<Any?>> = mutableListOf(),
        val clearedDrafts: MutableList<Unit> = mutableListOf(),
        val warnings: MutableList<Pair<String, Throwable?>> = mutableListOf(),
    )

    private fun Harness.controller(
        scope: CoroutineScope,
        stageError: Throwable? = null,
        token: String = "tok",
        requireSilentSend: Boolean = true,
    ) = ChatComposerSendController(
        scope = scope,
        ownerUserId = { "owner-1" },
        token = { token },
        activeChatId = { "c1" },
        chatId = { "c1" },
        currentState = { current },
        updateState = { transform -> current = transform(current) },
        textProvider = { id, args ->
            buildString {
                append("text:")
                append(id)
                args.forEach { append(":").append(it) }
            }
        },
        requireSilentSend = { requireSilentSend },
        composeContentWithMeta = { text, _ -> text },
        mergeMessages = ::mergeMessageVersions,
        clearDraft = { clearedDrafts += Unit },
        maybeForwardBotInbox = { liveToken, chatId, plaintext, isGroup, peerId ->
            botInbox += listOf(liveToken, chatId, plaintext, isGroup, peerId)
        },
        outgoingFacade = fakeFacade(staged, stageError),
        mentionsEnabled = { true },
        logWarning = { message, error -> warnings += message to error },
        sendInFlightRes = inFlightRes,
        sendEmptyRes = emptyRes,
        sessionExpiredRes = expiredRes,
        blockedUserStatusRes = blockedRes,
        mentionEveryoneRestrictedRes = mentionRes,
        sendFailedRes = failedRes,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun state(
        inputText: String = "hi",
        isSending: Boolean = false,
        silentSend: Boolean = false,
        isContactBlocked: Boolean = false,
        messages: List<Message> = emptyList(),
    ) = ChatDetailUiState(
        contact = peer,
        chat = chat,
        inputText = inputText,
        isSending = isSending,
        silentSend = silentSend,
        isContactBlocked = isContactBlocked,
        messages = messages,
    )

    @Test
    fun toggleSilentSendEnablesWhenAllowed() = runTest(dispatcher) {
        val harness = Harness(state())
        harness.controller(backgroundScope).toggleSilentSend()

        assertTrue(harness.current.silentSend)
    }

    @Test
    fun toggleSilentSendOffAlwaysWorks() = runTest(dispatcher) {
        val harness = Harness(state(silentSend = true))
        harness.controller(backgroundScope, requireSilentSend = false).toggleSilentSend()

        assertFalse(harness.current.silentSend)
    }

    @Test
    fun toggleSilentSendOnBlockedWhenNotAllowed() = runTest(dispatcher) {
        val harness = Harness(state())
        harness.controller(backgroundScope, requireSilentSend = false).toggleSilentSend()

        assertFalse(harness.current.silentSend)
    }

    @Test
    fun sendMessageBlankReturnsFalseWithWarning() = runTest(dispatcher) {
        val harness = Harness(state(inputText = "   "))
        val sent = harness.controller(backgroundScope).sendMessage()

        assertFalse(sent)
        assertEquals("text:$emptyRes", harness.current.groupEncryptionWarning)
        assertTrue(harness.current.messages.isEmpty())
        assertFalse(harness.current.isSending)
    }

    @Test
    fun sendMessageAlreadySendingReturnsFalse() = runTest(dispatcher) {
        val harness = Harness(state(isSending = true))
        val sent = harness.controller(backgroundScope).sendMessage()

        assertFalse(sent)
        assertEquals("text:$inFlightRes", harness.current.groupEncryptionWarning)
    }

    @Test
    fun sendMessageNoSessionReturnsFalse() = runTest(dispatcher) {
        val harness = Harness(state())
        val sent = harness.controller(backgroundScope, token = "").sendMessage()

        assertFalse(sent)
        assertEquals("text:$expiredRes", harness.current.groupEncryptionWarning)
    }

    @Test
    fun sendMessageBlockedContactReturnsFalse() = runTest(dispatcher) {
        val harness = Harness(state(isContactBlocked = true))
        val sent = harness.controller(backgroundScope).sendMessage()

        assertFalse(sent)
        assertEquals("text:$blockedRes:U2", harness.current.groupEncryptionWarning)
    }

    @Test
    fun sendMessageAllowStagesOptimisticAndCommits() = runTest(dispatcher) {
        val harness = Harness(state())
        val controller = harness.controller(backgroundScope)
        val sent = controller.sendMessage(
            onDurableCommit = { harness.commits += Unit },
            onDurableFailure = { harness.failures += Unit },
        )

        assertTrue(sent)
        assertEquals(1, harness.current.messages.size)
        assertEquals("hi", harness.current.messages[0].content)
        assertEquals(MessageStatus.SENDING, harness.current.messages[0].status)
        assertEquals("", harness.current.inputText)
        assertFalse(harness.current.isSending)
        assertEquals(1, harness.staged.size)
        assertEquals(1, harness.commits.size)
        assertEquals(0, harness.failures.size)
        assertEquals(1, harness.clearedDrafts.size)
        assertEquals(
            listOf(listOf<Any?>("tok", "c1", "hi", false, "u2")),
            harness.botInbox,
        )
    }

    @Test
    fun sendMessageStageFailureWarnsAndLogs() = runTest(dispatcher) {
        val harness = Harness(state())
        val controller = harness.controller(backgroundScope, stageError = RuntimeException("boom"))
        val sent = controller.sendMessage(
            onDurableCommit = { harness.commits += Unit },
            onDurableFailure = { harness.failures += Unit },
        )

        assertTrue(sent)
        assertEquals(1, harness.current.messages.size)
        assertEquals(MessageStatus.FAILED, harness.current.messages[0].status)
        assertFalse(harness.current.isSending)
        assertEquals("boom", harness.current.groupEncryptionWarning)
        assertEquals(0, harness.commits.size)
        assertEquals(1, harness.failures.size)
        assertEquals(0, harness.staged.size)
        assertTrue(harness.botInbox.isEmpty())
        assertEquals(1, harness.warnings.size)
        assertEquals("v2 text enqueue failed: boom", harness.warnings[0].first)
    }
}
