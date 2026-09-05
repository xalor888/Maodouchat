package com.maodouchat.quickreply

import com.maodouchat.notification.NotificationIntents
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.maodouchat.MaodouchatApp
import com.maodouchat.conversation.ConversationReadReceiptCoordinator
import com.maodouchat.domain.messaging.QuickReplyRequest
import com.maodouchat.domain.messaging.QuickReplyUseCase
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationIntentPolicy
import com.maodouchat.security.SecureSessionManager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test

class NotificationQuickReplyReceiverTest {

    private val context = mockk<Context>(relaxed = true)
    private val app = mockk<MaodouchatApp>(relaxed = true)
    private val tokenManager = mockk<TokenManager>()
    private val coordinator = mockk<ConversationReadReceiptCoordinator>(relaxed = true)
    private val quickReplyUseCase = mockk<QuickReplyUseCase>(relaxed = true)

    private val receiver = NotificationQuickReplyReceiver()

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0

        mockkObject(NotificationIntentPolicy)
        mockkObject(SecureSessionManager)
        mockkObject(QuickReplyPolicy)
        mockkObject(TokenManager.Companion)

        every { TokenManager.getInstance(any()) } returns tokenManager
        every { tokenManager.getUserId() } returns "user-1"
        every { SecureSessionManager.isPurgeInProgress() } returns false
        every { NotificationIntentPolicy.belongsToCurrentAccount(any(), any(), any()) } returns true
        every { context.applicationContext } returns app

        every { app.applicationScope } returns CoroutineScope(Dispatchers.Unconfined)
        every { app.conversationReadReceiptCoordinator } returns coordinator
        every { app.quickReplyUseCase } returns quickReplyUseCase

        coEvery { coordinator.markRead(any(), any()) } returns Result.success(Unit)
        coEvery { quickReplyUseCase.reply(any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createMockIntent(
        actionName: String?,
        chatId: String? = null,
        ownerUserId: String? = null,
    ): Intent {
        val intent = mockk<Intent>()
        every { intent.action } returns actionName
        every { intent.getStringExtra(NotificationQuickReplyReceiver.EXTRA_CHAT_ID) } returns chatId
        every { intent.getStringExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID) } returns ownerUserId
        return intent
    }

    @Test
    fun `ignores unknown action`() {
        val intent = createMockIntent("UNKNOWN_ACTION")
        receiver.onReceive(context, intent)

        coVerify(exactly = 0) { coordinator.markRead(any(), any()) }
        coVerify(exactly = 0) { quickReplyUseCase.reply(any()) }
    }

    @Test
    fun `ignores mismatched account`() {
        every {
            NotificationIntentPolicy.belongsToCurrentAccount("other-user", "user-1", false)
        } returns false

        val intent = createMockIntent(
            actionName = NotificationQuickReplyReceiver.ACTION_MARK_READ,
            chatId = "chat-1",
            ownerUserId = "other-user",
        )

        receiver.onReceive(context, intent)

        coVerify(exactly = 0) { coordinator.markRead(any(), any()) }
    }

    @Test
    fun `action mark read delegates to coordinator`() {
        val intent = createMockIntent(
            actionName = NotificationQuickReplyReceiver.ACTION_MARK_READ,
            chatId = "chat-1",
            ownerUserId = "user-1",
        )

        receiver.onReceive(context, intent)

        coVerify(exactly = 1) { coordinator.markRead("chat-1", "user-1") }
    }

    @Test
    fun `action reply with rejected gate does not forward to use case`() {
        mockkStatic(RemoteInput::class)
        val bundle = mockk<Bundle>()
        every { bundle.getCharSequence(NotificationQuickReplyReceiver.KEY_TEXT_REPLY) } returns "hello"
        every { RemoteInput.getResultsFromIntent(any()) } returns bundle
        every { QuickReplyPolicy.canAttemptReply(any(), any(), any()) } returns SyncVerdict.Rejected("disabled")

        val intent = createMockIntent(
            actionName = NotificationQuickReplyReceiver.ACTION_REPLY,
            chatId = "chat-1",
            ownerUserId = "user-1",
        )

        receiver.onReceive(context, intent)

        coVerify(exactly = 0) { quickReplyUseCase.reply(any()) }
    }

    @Test
    fun `action reply with allowed verdict delegates to quick reply use case`() {
        mockkStatic(RemoteInput::class)
        val bundle = mockk<Bundle>()
        every { bundle.getCharSequence(NotificationQuickReplyReceiver.KEY_TEXT_REPLY) } returns "hello"
        every { RemoteInput.getResultsFromIntent(any()) } returns bundle
        every {
            QuickReplyPolicy.canAttemptReply(context, "chat-1", "hello")
        } returns SyncVerdict.Allowed(text = "hello", ownerUserId = "user-1")
        every { QuickReplyPolicy.dedupeKey("user-1", "chat-1", "hello") } returns "k-123"

        val intent = createMockIntent(
            actionName = NotificationQuickReplyReceiver.ACTION_REPLY,
            chatId = "chat-1",
            ownerUserId = "user-1",
        )

        receiver.onReceive(context, intent)

        coVerify(exactly = 1) {
            quickReplyUseCase.reply(
                QuickReplyRequest(
                    conversationId = "chat-1",
                    text = "hello",
                    idempotencyKey = "k-123",
                )
            )
        }
    }
}
