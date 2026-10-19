package com.maodouchat.conversation

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.local.entity.MessageEntity
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import com.maodouchat.messaging.v2.MessagingV2Outbox
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationIntentPolicy
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.DefaultSecretConversationController
import com.maodouchat.security.SecureSessionManager

import com.maodouchat.widget.ConversationWidgetData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationReadReceiptCoordinatorTest {

    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val userDao = mockk<UserDao>(relaxed = true)
    private val outbox = mockk<MessagingV2Outbox>(relaxed = true)
    private val secretController = mockk<DefaultSecretConversationController>()
    private val tokenManager = mockk<TokenManager>()
    private val chatRepository = mockk<ChatRepository>(relaxed = true)

    private lateinit var coordinator: DefaultConversationReadReceiptCoordinator

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0

        every { database.chatDao() } returns chatDao
        every { database.messageDao() } returns messageDao
        every { database.userDao() } returns userDao

        mockkObject(NotificationIntentPolicy)
        mockkObject(SecureSessionManager)
        mockkObject(BackgroundSessionGate)
        mockkObject(MaodouchatApp)
        mockkObject(MessageNotificationService)
        mockkObject(ConversationWidgetData)

        every { SecureSessionManager.isPurgeInProgress() } returns false
        every { tokenManager.getUserId() } returns "user-1"
        every { tokenManager.getToken() } returns "token-1"
        every { NotificationIntentPolicy.belongsToCurrentAccount(any(), any(), any()) } returns true
        every { BackgroundSessionGate.mayContinue("user-1", "token-1", "user-1") } returns true

        every { MaodouchatApp.emitChatRead(any()) } returns Unit
        every { MessageNotificationService.cancelMessage(any(), any()) } returns Unit
        every { ConversationWidgetData.refreshAll(any()) } returns Unit

        coEvery { secretController.capabilities(any()) } returns ConversationPrivacyCapabilities(
            isSecretChat = false,
            isLocked = false,
        )

        coordinator = DefaultConversationReadReceiptCoordinator(
            context = context,
            database = database,
            outbox = outbox,
            secretConversationController = secretController,
            tokenManager = tokenManager,
            chatRepository = chatRepository,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `blank chatId fails with blank_chat_id`() = runTest {
        val result = coordinator.markRead("")
        assertTrue(result.isFailure)
        assertEquals("blank_chat_id", result.exceptionOrNull()?.message)
    }

    @Test
    fun `account mismatch fails with account_mismatch`() = runTest {
        every {
            NotificationIntentPolicy.belongsToCurrentAccount("other-user", "user-1", false)
        } returns false

        val result = coordinator.markRead("chat-1", expectedUserId = "other-user")
        assertTrue(result.isFailure)
        assertEquals("account_mismatch", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invalid session fails with invalid_session`() = runTest {
        every { BackgroundSessionGate.mayContinue(any(), any(), any()) } returns false

        val result = coordinator.markRead("chat-1")
        assertTrue(result.isFailure)
        assertEquals("invalid_session", result.exceptionOrNull()?.message)
    }

    @Test
    fun `secret chat skips outbox enqueue but updates local chat read and cancels notification`() = runTest {
        coEvery { secretController.capabilities("secret-chat") } returns ConversationPrivacyCapabilities(
            isSecretChat = true,
            isLocked = false,
        )

        val result = coordinator.markRead("secret-chat")
        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { outbox.enqueueReadReceipt(any(), any(), any()) }
        coVerify(exactly = 1) { chatRepository.markChatRead("secret-chat") }
        coVerify(exactly = 1) { MaodouchatApp.emitChatRead("secret-chat") }
        coVerify(exactly = 1) { MessageNotificationService.cancelMessage(context, "secret-chat") }
        coVerify(exactly = 1) { ConversationWidgetData.refreshAll(context) }
    }

    @Test
    fun `non-secret chat with incoming message enqueues read receipt then marks local read`() = runTest {
        coEvery { messageDao.getLatestIncomingMessage("chat-1", "user-1") } returns MessageEntity(
            id = "msg-9",
            chatId = "chat-1",
            senderId = "other-user",
            content = "hello",
        )

        val result = coordinator.markRead("chat-1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            outbox.enqueueReadReceipt(
                conversationId = "chat-1",
                throughMessageId = "msg-9",
                groupRevision = null,
            )
        }
        coVerify(exactly = 1) { chatRepository.markChatRead("chat-1") }
        coVerify(exactly = 1) { MaodouchatApp.emitChatRead("chat-1") }
        coVerify(exactly = 1) { MessageNotificationService.cancelMessage(context, "chat-1") }
        coVerify(exactly = 1) { ConversationWidgetData.refreshAll(context) }
    }

    @Test
    fun `outbox enqueue failure is swallowed and local read still succeeds`() = runTest {
        coEvery { messageDao.getLatestIncomingMessage("chat-1", "user-1") } returns MessageEntity(
            id = "msg-9",
            chatId = "chat-1",
            senderId = "other-user",
            content = "hello",
        )
        coEvery { outbox.enqueueReadReceipt(any(), any(), any()) } throws RuntimeException("outbox down")

        val result = coordinator.markRead("chat-1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.markChatRead("chat-1") }
    }

    @Test
    fun `local mark chat read failure surfaces as result failure`() = runTest {
        coEvery { chatRepository.markChatRead(any()) } throws IllegalStateException("db gone")

        val result = coordinator.markRead("chat-1")

        assertTrue(result.isFailure)
        assertEquals("db gone", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation during chat lookup propagates instead of becoming failure`() = runTest {
        coEvery { chatDao.getChatById(any()) } throws CancellationException()

        assertFailsWith<CancellationException> {
            coordinator.markRead("chat-1")
        }
    }

    @Test
    fun `repeated mark read succeeds without deduplication`() = runTest {
        coEvery { messageDao.getLatestIncomingMessage("chat-1", "user-1") } returns MessageEntity(
            id = "msg-9",
            chatId = "chat-1",
            senderId = "other-user",
            content = "hello",
        )

        val first = coordinator.markRead("chat-1")
        val second = coordinator.markRead("chat-1")

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        coVerify(exactly = 2) {
            outbox.enqueueReadReceipt(
                conversationId = "chat-1",
                throughMessageId = "msg-9",
                groupRevision = null,
            )
        }
    }
}
