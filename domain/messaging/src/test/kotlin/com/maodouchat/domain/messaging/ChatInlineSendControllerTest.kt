package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.OutgoingConversationErrors
import com.maodouchat.core.model.OutgoingConversationRequest
import com.maodouchat.core.model.User
import com.maodouchat.core.model.mergeMessageVersions
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInlineSendControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    // res id 替身：domain 测试看不到 app R。
    private val expiredRes = 1003
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
        val sentEvents: MutableList<Triple<String, String, String>> = mutableListOf(),
        val completions: ConcurrentHashMap<String, CompletableDeferred<Boolean>> = ConcurrentHashMap(),
    )

    private fun Harness.controller(
        scope: CoroutineScope,
        stageError: Throwable? = null,
        token: String = "tok",
        ownerUserId: String = "owner-1",
    ) = ChatInlineSendController(
        scope = scope,
        ownerUserId = { ownerUserId },
        token = { token },
        activeChatId = { "c1" },
        updateState = { transform -> current = transform(current) },
        textProvider = { id, args ->
            buildString {
                append("text:")
                append(id)
                args.forEach { append(":").append(it) }
            }
        },
        mergeMessages = ::mergeMessageVersions,
        completions = completions,
        outgoingFacade = fakeFacade(staged, stageError),
        onMessageSent = { chatId, preview, typeName -> sentEvents += Triple(chatId, preview, typeName) },
        sessionExpiredRes = expiredRes,
        sendFailedRes = failedRes,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun state(messages: List<Message> = emptyList()) = ChatDetailUiState(
        contact = peer,
        chat = chat,
        messages = messages,
    )

    @Test
    fun blankTokenReturnsNullWithWarning() = runTest(dispatcher) {
        val harness = Harness(state())
        val sent = harness.controller(backgroundScope, token = "").sendInlineContent(
            "sticker-1", MessageType.STICKER, "preview-sticker",
        )

        assertNull(sent)
        assertEquals("text:$expiredRes", harness.current.groupEncryptionWarning)
        assertFalse(harness.current.isSending)
        assertTrue(harness.current.messages.isEmpty())
        assertTrue(harness.sentEvents.isEmpty())
    }

    @Test
    fun blankOwnerReturnsNullWithWarning() = runTest(dispatcher) {
        val harness = Harness(state())
        val sent = harness.controller(backgroundScope, ownerUserId = "").sendInlineContent(
            "sticker-1", MessageType.STICKER, "preview-sticker",
        )

        assertNull(sent)
        assertEquals("text:$expiredRes", harness.current.groupEncryptionWarning)
        assertFalse(harness.current.isSending)
    }

    @Test
    fun stagedSuccessOptimisticThenStagedAndEmits() = runTest(dispatcher) {
        val harness = Harness(state())
        val controller = harness.controller(backgroundScope)
        val sent = controller.sendInlineContent("sticker-1", MessageType.STICKER, "preview-sticker")

        assertTrue(sent != null && sent.startsWith("m_"))
        assertEquals(1, harness.current.messages.size)
        assertEquals("sticker-1", harness.current.messages[0].content)
        assertEquals(MessageType.STICKER, harness.current.messages[0].type)
        assertEquals(sent, harness.current.messages[0].id)
        assertEquals(MessageStatus.SENDING, harness.current.messages[0].status)
        assertFalse(harness.current.isSending)
        assertEquals(1, harness.staged.size)
        assertEquals(listOf(Triple("c1", "preview-sticker", "STICKER")), harness.sentEvents)
        assertTrue(harness.completions.isEmpty())
    }

    @Test
    fun failedResultWarnsWithErrorMessage() = runTest(dispatcher) {
        val harness = Harness(state())
        val controller = harness.controller(backgroundScope, stageError = RuntimeException("boom"))
        val sent = controller.sendInlineContent("sticker-1", MessageType.STICKER, "preview-sticker")

        assertTrue(sent != null && sent.startsWith("m_"))
        assertEquals(1, harness.current.messages.size)
        assertEquals(MessageStatus.FAILED, harness.current.messages[0].status)
        assertFalse(harness.current.isSending)
        assertEquals("boom", harness.current.groupEncryptionWarning)
        assertEquals(0, harness.staged.size)
        assertTrue(harness.sentEvents.isEmpty())
        assertTrue(harness.completions.isEmpty())
    }

    @Test
    fun failedResultFallsBackToFailedText() = runTest(dispatcher) {
        val harness = Harness(state())
        val controller = harness.controller(backgroundScope, stageError = RuntimeException())
        controller.sendInlineContent("sticker-1", MessageType.STICKER, "preview-sticker")

        assertEquals(MessageStatus.FAILED, harness.current.messages[0].status)
        assertEquals("text:$failedRes", harness.current.groupEncryptionWarning)
        assertTrue(harness.sentEvents.isEmpty())
    }

    @Test
    fun failedErrorMessageTruncatedTo120() = runTest(dispatcher) {
        val harness = Harness(state())
        val controller = harness.controller(backgroundScope, stageError = RuntimeException("x".repeat(200)))
        controller.sendInlineContent("sticker-1", MessageType.STICKER, "preview-sticker")

        assertEquals("x".repeat(120), harness.current.groupEncryptionWarning)
    }
}
