package com.maodouchat.attachment

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentKind
import com.maodouchat.domain.messaging.AttachmentPreparationService
import com.maodouchat.domain.messaging.AttachmentTransfer
import com.maodouchat.domain.messaging.ContentPayload
import com.maodouchat.domain.messaging.ConversationCommandFacade
import com.maodouchat.domain.messaging.PreparedAttachmentResult
import com.maodouchat.domain.messaging.SendMessageCommand
import com.maodouchat.domain.messaging.SendMessageResult
import com.maodouchat.domain.messaging.TransferRepository
import com.maodouchat.domain.messaging.TransferStatus
import com.maodouchat.network.TokenManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AttachmentIntentControllerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockTokenManager: TokenManager
    private lateinit var fakeTransferRepo: FakeTransferRepository
    private lateinit var fakePrepService: FakePreparationService
    private lateinit var fakeCommandFacade: FakeCommandFacade

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockTokenManager = mockk(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getBoolean(any(), any()) } returns true

        every { mockTokenManager.getUserId() } returns "user-owner"

        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot(mockTokenManager.getToken(), mockTokenManager.getUserId()) }
        every { mockTokenManager.getToken() } returns "valid-token"

        fakeTransferRepo = FakeTransferRepository()
        fakePrepService = FakePreparationService()
        fakeCommandFacade = FakeCommandFacade()
    }

    private fun createController(
        tokenManager: TokenManager = mockTokenManager,
        commandFacade: ConversationCommandFacade? = fakeCommandFacade,
        ownerUserId: () -> String = { "user-owner" },
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
    ): DefaultAttachmentIntentController {
        return DefaultAttachmentIntentController(
            context = mockContext,
            transferRepository = fakeTransferRepo,
            preparationService = fakePrepService,
            messageStore = null,
            tokenManager = tokenManager,
            commandFacade = commandFacade,
            ownerUserId = ownerUserId,
            onProgress = onProgress,
        )
    }

    @Test
    fun `submit sticker delegates to command facade with text payload`() = runTest {
        val controller = createController()
        val intent = AttachmentIntent(
            conversationId = "chat-1",
            kind = AttachmentKind.STICKER,
            uri = "sticker://cat_thumbs_up",
            idempotencyKey = "msg-sticker-1",
        )

        val result = controller.submit(intent)

        assertTrue(result.isSuccess)
        assertEquals("msg-sticker-1", result.getOrNull())
        assertEquals(1, fakeCommandFacade.sentCommands.size)
        val cmd = fakeCommandFacade.sentCommands.single()
        assertEquals("chat-1", cmd.conversationId)
        assertTrue(cmd.content is ContentPayload.Text)
        assertEquals("cat_thumbs_up", (cmd.content as ContentPayload.Text).text)
    }

    @Test
    fun `submit location parses geo uri and sends Location payload`() = runTest {
        val controller = createController()
        val intent = AttachmentIntent(
            conversationId = "chat-1",
            kind = AttachmentKind.LOCATION,
            uri = "geo:39.9042,116.4074?name=Beijing",
            idempotencyKey = "msg-loc-1",
        )

        val result = controller.submit(intent)

        assertTrue(result.isSuccess)
        assertEquals("msg-loc-1", result.getOrNull())
        val cmd = fakeCommandFacade.sentCommands.single()
        assertTrue(cmd.content is ContentPayload.Location)
        val loc = cmd.content as ContentPayload.Location
        assertEquals(39.9042, loc.latitude, 0.0001)
        assertEquals(116.4074, loc.longitude, 0.0001)
    }

    @Test
    fun `submit contact parses contact uri and sends Contact payload`() = runTest {
        val controller = createController()
        val intent = AttachmentIntent(
            conversationId = "chat-1",
            kind = AttachmentKind.CONTACT,
            uri = "contact://friend_001?name=Bob",
            idempotencyKey = "msg-contact-1",
        )

        val result = controller.submit(intent)

        assertTrue(result.isSuccess)
        assertEquals("msg-contact-1", result.getOrNull())
        val cmd = fakeCommandFacade.sentCommands.single()
        assertTrue(cmd.content is ContentPayload.Contact)
        val contact = cmd.content as ContentPayload.Contact
        assertEquals("friend_001", contact.userId)
        assertEquals("Bob", contact.displayName)
    }

    @Test
    fun `submit rejects when owner is blank or session expired`() = runTest {
        val controller = createController(
            ownerUserId = { "" }
        )
        val intent = AttachmentIntent(
            conversationId = "chat-1",
            kind = AttachmentKind.STICKER,
            uri = "sticker://doge",
        )

        val result = controller.submit(intent)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `control operations pause resume cancel delegate to transfer repository`() = runTest {
        val controller = createController()

        assertTrue(controller.pause("transfer-1"))
        assertEquals(listOf("pause:transfer-1"), fakeTransferRepo.calls)

        assertTrue(controller.resume("transfer-1"))
        assertEquals(listOf("pause:transfer-1", "resume:transfer-1"), fakeTransferRepo.calls)

        assertTrue(controller.cancel("transfer-1"))
        assertEquals(listOf("pause:transfer-1", "resume:transfer-1", "cancel:transfer-1"), fakeTransferRepo.calls)
    }

    @Test
    fun `observe transfer returns flow from repository`() = runTest {
        val controller = createController()
        fakeTransferRepo.transfers["t-1"] = AttachmentTransfer(
            transferId = "t-1",
            status = TransferStatus.UPLOADING,
            progressBytes = 500,
            totalBytes = 1000,
        )

        val flow = controller.observe("t-1")
        var observed: AttachmentTransfer? = null
        flow.collect { observed = it }

        assertEquals("t-1", observed?.transferId)
        assertEquals(TransferStatus.UPLOADING, observed?.status)
    }

    // Fakes
    private class FakeTransferRepository : TransferRepository {
        val calls = mutableListOf<String>()
        val transfers = mutableMapOf<String, AttachmentTransfer>()

        override fun observe(transferId: String, ownerUserId: String): Flow<AttachmentTransfer?> {
            return flowOf(transfers[transferId])
        }

        override fun observeAll(chatId: String, ownerUserId: String): Flow<List<AttachmentTransfer>> {
            return flowOf(transfers.values.toList())
        }

        override suspend fun get(transferId: String, ownerUserId: String): AttachmentTransfer? {
            return transfers[transferId]
        }

        override suspend fun updateStatus(transferId: String, ownerUserId: String, status: TransferStatus): Boolean {
            calls += "updateStatus:$transferId:$status"
            return true
        }

        override suspend fun updateProgress(
            transferId: String,
            ownerUserId: String,
            progressBytes: Long,
            totalBytes: Long?
        ): Boolean {
            calls += "updateProgress:$transferId:$progressBytes"
            return true
        }

        override suspend fun pause(transferId: String, ownerUserId: String): Boolean {
            calls += "pause:$transferId"
            return true
        }

        override suspend fun resume(transferId: String, ownerUserId: String): Boolean {
            calls += "resume:$transferId"
            return true
        }

        override suspend fun cancel(transferId: String, ownerUserId: String): Boolean {
            calls += "cancel:$transferId"
            return true
        }
    }

    private class FakePreparationService : AttachmentPreparationService {
        override suspend fun prepare(
            intent: AttachmentIntent,
            ownerUserId: String,
            onProgress: (completed: Long, total: Long) -> Unit
        ): Result<PreparedAttachmentResult> {
            onProgress(50, 100)
            return Result.success(
                PreparedAttachmentResult(
                    messageId = intent.idempotencyKey,
                    sourceUri = intent.uri,
                    encryptedPath = "/fake/path",
                    fileName = "file.dat",
                    mimeType = "application/octet-stream",
                    plainSize = 100,
                    durationMs = null,
                    keyBase64 = "key",
                    ivBase64 = "iv",
                    cipherSha256 = "csha",
                    plainSha256 = "psha",
                    cipherSize = 116
                )
            )
        }
    }

    private class FakeCommandFacade : ConversationCommandFacade {
        val sentCommands = mutableListOf<SendMessageCommand>()

        override suspend fun send(command: SendMessageCommand): SendMessageResult {
            sentCommands += command
            return SendMessageResult.Success(command.idempotencyKey, durableCommitted = true)
        }

        override suspend fun cancel(localMessageId: String): Boolean = true
        override suspend fun retry(localMessageId: String): SendMessageResult =
            SendMessageResult.Success(localMessageId, durableCommitted = true)
    }
}
