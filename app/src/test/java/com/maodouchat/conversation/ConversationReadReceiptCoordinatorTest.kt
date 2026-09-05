package com.maodouchat.conversation

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
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
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
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
}
