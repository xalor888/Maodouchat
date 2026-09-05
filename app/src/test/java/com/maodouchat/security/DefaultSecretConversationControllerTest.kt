package com.maodouchat.security

import android.content.Context
import android.util.Log
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.ChatLockDao
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.MessageSearchDao
import com.maodouchat.data.local.dao.SecretChatDao
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.domain.messaging.PrivacyAction
import com.maodouchat.network.TokenManager
import com.maodouchat.util.MediaCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSecretConversationControllerTest {

    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>()
    private val chatDao = mockk<ChatDao>()
    private val chatLockDao = mockk<ChatLockDao>()
    private val messageDao = mockk<MessageDao>()
    private val messageSearchDao = mockk<MessageSearchDao>()
    private val secretChatDao = mockk<SecretChatDao>()
    private val tokenManager = mockk<TokenManager>(relaxed = true)

    private val secretChatFlow = MutableStateFlow<List<String>>(listOf("secret-1"))
    private val lockedChatFlow = MutableStateFlow<List<String>>(listOf("locked-1"))

    private var activeScope: CoroutineScope? = null

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(DisappearingMessageDestructionWorker.Companion)
        every { DisappearingMessageDestructionWorker.schedule(any(), any(), any(), any()) } returns Unit
        every { DisappearingMessageDestructionWorker.cancel(any(), any()) } returns Unit

        mockkObject(MediaCache)
        every { MediaCache.deleteSecretChatMedia(any(), any()) } returns true
        every { MediaCache.deleteCachedMediaForMessage(any(), any()) } returns true

        mockkObject(TokenManager.Companion)
        every { TokenManager.getInstance(any()) } returns tokenManager
        every { tokenManager.getUserId() } returns "user-1"

        every { database.chatDao() } returns chatDao
        every { database.chatLockDao() } returns chatLockDao
        every { database.messageDao() } returns messageDao
        every { database.messageSearchDao() } returns messageSearchDao
        every { database.secretChatDao() } returns secretChatDao

        coEvery { chatDao.listSecretChatIds() } returns listOf("secret-1")
        coEvery { chatLockDao.listLockedChatIds() } returns listOf("locked-1")
        every { chatDao.isSecretChatBlocking("secret-1") } returns true
        every { chatDao.isSecretChatBlocking(not("secret-1")) } returns false
        every { chatLockDao.isChatLockedBlocking("locked-1") } returns true
        every { chatLockDao.isChatLockedBlocking(not("locked-1")) } returns false

        every { chatDao.observeSecretChatIds() } returns secretChatFlow
        every { chatLockDao.observeLockedChatIds() } returns lockedChatFlow

        ChatLockSession.clearAll()
    }

    @After
    fun tearDown() {
        activeScope?.cancel()
        ChatLockSession.clearAll()
        unmockkAll()
    }

    @Test
    fun `capabilities caches secret and locked chats reactively`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(testDispatcher + Job())
        activeScope = controllerScope
        val controller = DefaultSecretConversationController(context, database, controllerScope, testDispatcher)
        advanceUntilIdle()

        val capsSecret = controller.capabilities("secret-1")
        assertTrue(capsSecret.isSecretChat)
        assertFalse(capsSecret.isLocked)

        val capsLocked = controller.capabilities("locked-1")
        assertFalse(capsLocked.isSecretChat)
        assertTrue(capsLocked.isLocked)

        val capsNormal = controller.capabilities("normal-1")
        assertFalse(capsNormal.isSecretChat)
        assertFalse(capsNormal.isLocked)

        // Session unlock lifts isLocked flag in capabilities
        ChatLockSession.markUnlocked("locked-1")
        val capsUnlocked = controller.capabilities("locked-1")
        assertFalse(capsUnlocked.isLocked)

        controllerScope.cancel()
    }

    @Test
    fun `isAllowed correctly delegates to ConversationPrivacyPolicy`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(testDispatcher + Job())
        activeScope = controllerScope
        val controller = DefaultSecretConversationController(context, database, controllerScope, testDispatcher)
        advanceUntilIdle()

        assertFalse(controller.isAllowed("secret-1", PrivacyAction.AI))
        assertFalse(controller.isAllowed("secret-1", PrivacyAction.SEARCH))
        assertFalse(controller.isAllowed("secret-1", PrivacyAction.SCREENSHOT))
        assertFalse(controller.isAllowed("secret-1", PrivacyAction.EXPORT))

        assertFalse(controller.isAllowed("locked-1", PrivacyAction.SEARCH))
        assertTrue(controller.isAllowed("locked-1", PrivacyAction.AI))

        assertTrue(controller.isAllowed("normal-1", PrivacyAction.AI))
        assertTrue(controller.isAllowed("normal-1", PrivacyAction.SEARCH))

        controllerScope.cancel()
    }

    @Test
    fun `armOnRead arms unread messages atomically and sets expiry`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(testDispatcher + Job())
        activeScope = controllerScope
        val controller = DefaultSecretConversationController(context, database, controllerScope, testDispatcher)
        advanceUntilIdle()

        coEvery { chatDao.getChatById("secret-1") } returns ChatEntity(
            id = "secret-1",
            chatType = "SECRET",
            disappearingMessageSeconds = 0
        )
        coEvery { messageDao.armMessagesOnRead("secret-1", any()) } returns 3

        controller.armOnRead("secret-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { messageDao.armMessagesOnRead("secret-1", any()) }
        coVerify(exactly = 1) {
            DisappearingMessageDestructionWorker.schedule(
                context = context,
                delayMs = any(),
                chatId = "secret-1",
                ownerUserId = "user-1"
            )
        }

        controllerScope.cancel()
    }

    @Test
    fun `destroy purges messages search index and secret heartbeat`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(testDispatcher + Job())
        activeScope = controllerScope
        val controller = DefaultSecretConversationController(context, database, controllerScope, testDispatcher)
        advanceUntilIdle()

        coEvery { messageDao.deleteMessagesByChatId("secret-1") } returns Unit
        coEvery { messageSearchDao.deleteChatIndex("secret-1") } returns Unit
        coEvery { secretChatDao.remove("secret-1") } returns Unit

        controller.destroy("secret-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { messageDao.deleteMessagesByChatId("secret-1") }
        coVerify(exactly = 1) { messageSearchDao.deleteChatIndex("secret-1") }
        coVerify(exactly = 1) { secretChatDao.remove("secret-1") }

        controllerScope.cancel()
    }

    @Test
    fun `purgeExpiredMessages deletes expired items and cleans search index`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(testDispatcher + Job())
        activeScope = controllerScope
        val controller = DefaultSecretConversationController(context, database, controllerScope, testDispatcher)
        advanceUntilIdle()

        val expired = listOf("msg-1", "msg-2")
        coEvery { messageDao.getExpiredMessageIds(any()) } returns expired
        coEvery { messageDao.deleteMessagesByIds(expired) } returns Unit
        coEvery { messageSearchDao.deleteDocument(any()) } returns Unit
        coEvery { messageDao.getEarliestExpiryAfter(any()) } returns null

        val purged = controller.purgeExpiredMessages(nowMs = 100000L)

        assertEquals(expired, purged)
        coVerify(exactly = 1) { messageDao.deleteMessagesByIds(expired) }
        coVerify(exactly = 1) { messageSearchDao.deleteDocument("msg-1") }
        coVerify(exactly = 1) { messageSearchDao.deleteDocument("msg-2") }

        controllerScope.cancel()
    }
}
