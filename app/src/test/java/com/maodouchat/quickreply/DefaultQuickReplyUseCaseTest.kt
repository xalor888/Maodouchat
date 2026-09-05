package com.maodouchat.quickreply

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.domain.messaging.ContentPayload
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import com.maodouchat.domain.messaging.QuickReplyRequest
import com.maodouchat.domain.messaging.SendMessageCommand
import com.maodouchat.domain.messaging.SendMessageResult
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.DefaultSecretConversationController

import com.maodouchat.widget.ConversationWidgetData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultQuickReplyUseCaseTest {

    private val context = mockk<Context>(relaxed = true)
    private val commandFacade = mockk<ConversationCommandFacade>()
    private val secretController = mockk<DefaultSecretConversationController>()
    private val tokenManager = mockk<TokenManager>()

    private lateinit var useCase: DefaultQuickReplyUseCase

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0

        mockkObject(QuickReplyPolicy)
        mockkObject(BackgroundSessionGate)
        mockkObject(MessageNotificationService)
        mockkObject(ConversationWidgetData)
        mockkObject(com.maodouchat.MaodouchatApp)

        every { MessageNotificationService.cancelMessage(any(), any()) } returns Unit
        every { ConversationWidgetData.refreshAll(any()) } returns Unit
        every { com.maodouchat.MaodouchatApp.emitMessageSent(any(), any(), any(), any()) } returns Unit

        every { tokenManager.getUserId() } returns "user-123"
        every { tokenManager.getToken() } returns "valid-token"
        every { BackgroundSessionGate.mayContinue("user-123", "valid-token", "user-123") } returns true
        every { QuickReplyPolicy.isEnabled(any()) } returns true
        every { QuickReplyPolicy.sanitizeReplyText(any()) } answers { firstArg<String>().trim() }
        every { QuickReplyPolicy.dedupeKey(any(), any(), any()) } answers { "dedupe-${firstArg<String>()}" }
        every { QuickReplyPolicy.shouldSuppressDuplicate(any(), any(), any(), any()) } returns false
        every { QuickReplyPolicy.rememberSent(any(), any(), any(), any()) } returns Unit

        coEvery { secretController.capabilities(any()) } returns ConversationPrivacyCapabilities(
            isSecretChat = false,
            isLocked = false,
        )

        useCase = DefaultQuickReplyUseCase(
            context = context,
            commandFacade = commandFacade,
            secretConversationController = secretController,
            tokenManager = tokenManager,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `blank conversationId fails validation`() = runTest {
        val result = useCase.reply(QuickReplyRequest("", "hello", "k1"))
        assertTrue(result.isFailure)
        assertEquals("invalid_input", result.exceptionOrNull()?.message)
    }

    @Test
    fun `whitespace text fails validation`() = runTest {
        val result = useCase.reply(QuickReplyRequest("chat-1", "   ", "k1"))
        assertTrue(result.isFailure)
        assertEquals("invalid_input", result.exceptionOrNull()?.message)
    }

    @Test
    fun `disabled quick reply fails with quick_reply_disabled`() = runTest {
        every { QuickReplyPolicy.isEnabled(any()) } returns false
        val result = useCase.reply(QuickReplyRequest("chat-1", "hello", "k1"))
        assertTrue(result.isFailure)
        assertEquals("quick_reply_disabled", result.exceptionOrNull()?.message)
    }

    @Test
    fun `unauthenticated session fails with not_logged_in`() = runTest {
        every { tokenManager.getUserId() } returns null
        val result = useCase.reply(QuickReplyRequest("chat-1", "hello", "k1"))
        assertTrue(result.isFailure)
        assertEquals("not_logged_in", result.exceptionOrNull()?.message)
    }

    @Test
    fun `secret chat is rejected`() = runTest {
        coEvery { secretController.capabilities("chat-secret") } returns ConversationPrivacyCapabilities(
            isSecretChat = true,
            isLocked = false,
        )
        val result = useCase.reply(QuickReplyRequest("chat-secret", "hello", "k1"))
        assertTrue(result.isFailure)
        assertEquals("secret_chat", result.exceptionOrNull()?.message)
    }

    @Test
    fun `locked chat is rejected`() = runTest {
        coEvery { secretController.capabilities("chat-locked") } returns ConversationPrivacyCapabilities(
            isSecretChat = false,
            isLocked = true,
        )
        val result = useCase.reply(QuickReplyRequest("chat-locked", "hello", "k1"))
        assertTrue(result.isFailure)
        assertEquals("chat_locked", result.exceptionOrNull()?.message)
    }

    @Test
    fun `duplicate reply is suppressed gracefully`() = runTest {
        every { QuickReplyPolicy.shouldSuppressDuplicate(any(), any(), any(), any()) } returns true
        val result = useCase.reply(QuickReplyRequest("chat-1", "hello", "k1"))
        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { commandFacade.send(any()) }
    }

    @Test
    fun `valid reply successfully sends via command facade and triggers cleanup`() = runTest {
        coEvery { commandFacade.send(any()) } returns SendMessageResult.Success("m-999", durableCommitted = true)

        val result = useCase.reply(QuickReplyRequest("chat-1", "hello", "k1"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            commandFacade.send(
                SendMessageCommand(
                    conversationId = "chat-1",
                    content = ContentPayload.Text("hello"),
                    idempotencyKey = "k1",
                )
            )
        }
        coVerify { MessageNotificationService.cancelMessage(context, "chat-1") }
        coVerify { com.maodouchat.MaodouchatApp.emitMessageSent("chat-1", "hello", "TEXT", false) }
        coVerify { ConversationWidgetData.refreshAll(context) }
    }
}
