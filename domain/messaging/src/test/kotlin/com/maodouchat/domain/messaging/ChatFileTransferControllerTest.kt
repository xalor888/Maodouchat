package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.AttachmentCryptoException
import com.maodouchat.core.crypto.AttachmentCryptoFailure
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.AttachmentReferenceInvalidException
import com.maodouchat.core.model.AttachmentTooLargeException
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.domain.conversation.AttachmentTransferState
import com.maodouchat.domain.conversation.ChatMediaStateController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatFileTransferControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun fileMessage(id: String, content: String = "file:///local/$id") =
        Message(id = id, chatId = "c1", senderId = "u2", content = content, type = MessageType.FILE)

    private fun imageMessage(id: String, content: String = "file:///local/$id") =
        Message(id = id, chatId = "c1", senderId = "u2", content = content, type = MessageType.IMAGE)

    private class FakeIntentController : AttachmentIntentController {
        var pauseResult = true
        var resumeResult = true
        var cancelResult = true
        val pauseCalls = mutableListOf<String>()
        val resumeCalls = mutableListOf<String>()
        val cancelCalls = mutableListOf<String>()
        override suspend fun submit(intent: AttachmentIntent): Result<String> = Result.success("x")
        override fun observe(transferId: String): Flow<AttachmentTransfer> = emptyFlow()
        override suspend fun pause(transferId: String): Boolean {
            pauseCalls += transferId
            return pauseResult
        }
        override suspend fun resume(transferId: String): Boolean {
            resumeCalls += transferId
            return resumeResult
        }
        override suspend fun cancel(transferId: String): Boolean {
            cancelCalls += transferId
            return cancelResult
        }
    }

    private inner class Harness(
        initial: ChatDetailUiState,
        private val dispatcher: CoroutineDispatcher,
    ) {
        val uiState = MutableStateFlow(initial)
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)
        val applicationScope = CoroutineScope(dispatcher + Job())
        val intent = FakeIntentController()
        val mediaStateController = ChatMediaStateController()
        val preparationJobs = mutableMapOf<String, Job>()

        var activeChatId = "c1"
        var ensureResult: Result<Message> = Result.success(fileMessage("m1"))
        val ensureCalls = mutableListOf<Message>()
        var retryCalls = mutableListOf<String>()
        var cancelAllCalls = mutableListOf<String>()
        val readableUris = mutableSetOf<String>()
        val releasedPermissions = mutableListOf<String>()
        val deletedMedia = mutableListOf<String>()
        val statusUpdates = mutableListOf<Pair<String, MessageStatus>>()
        val deletedMessages = mutableListOf<String>()
        var currentUserId = "owner-1"
        val cleanupCalls = mutableListOf<Pair<String, String>>()
        var cleanupThrows: Throwable? = null
        val warnings = mutableListOf<Pair<String, Throwable>>()

        fun controller() = ChatFileTransferController(
            uiState = uiState,
            scope = scope,
            applicationScope = applicationScope,
            activeChatId = { activeChatId },
            mediaStateController = mediaStateController,
            attachmentIntentController = intent,
            attachmentPreparationJobs = preparationJobs,
            text = { key -> key.name },
            ensureLocalAttachment = { message ->
                ensureCalls += message
                ensureResult
            },
            retryAllTransfers = { chatId -> retryCalls += chatId },
            cancelAllTransfers = { chatId -> cancelAllCalls += chatId },
            isReadableLocalUri = { uri -> uri in readableUris },
            releasePersistableReadPermission = { uri -> releasedPermissions += uri },
            deleteCachedMediaForMessage = { messageId -> deletedMedia += messageId },
            updateMessageStatus = { messageId, status -> statusUpdates += messageId to status },
            deleteMessage = { messageId -> deletedMessages += messageId },
            currentUserId = { currentUserId },
            cleanupAttachmentTransfer = { messageId, ownerUserId ->
                cleanupThrows?.let { throw it }
                cleanupCalls += messageId to ownerUserId
            },
            logWarning = { message, error -> warnings += message to error },
            ioDispatcher = dispatcher,
        )
    }

    private fun state(
        messages: List<Message> = emptyList(),
        fileTransferStates: Map<String, String> = emptyMap(),
        downloading: Set<String> = emptySet(),
        preparing: Set<String> = emptySet(),
    ) = ChatDetailUiState(
        messages = messages,
        fileTransferStates = fileTransferStates,
        downloadingFileMessageIds = downloading,
        preparingAttachmentMessageIds = preparing,
    )

    @Test
    fun requestOpenFileLocalReadableSetsUriWithoutDownload() {
        val message = fileMessage("m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.readableUris += message.parsedContent()
        val controller = harness.controller()
        controller.requestOpenFile("m1")
        assertEquals(message.parsedContent(), harness.uiState.value.fileReadyToOpenUri)
        assertTrue(harness.uiState.value.downloadingFileMessageIds.isEmpty())
        assertTrue(harness.ensureCalls.isEmpty())
    }

    @Test
    fun requestOpenFileDownloadsAndFinishesWithLocalUri() {
        val message = fileMessage("m1")
        val local = fileMessage("m1", content = "file:///cache/m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.ensureResult = Result.success(local)
        val controller = harness.controller()
        controller.requestOpenFile("m1")
        assertEquals("file:///cache/m1", harness.uiState.value.fileReadyToOpenUri)
        assertTrue(harness.uiState.value.downloadingFileMessageIds.isEmpty())
        assertEquals(listOf(message), harness.ensureCalls)
    }

    @Test
    fun requestOpenFileFailureShowsDownloadFailed() {
        val message = fileMessage("m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.ensureResult = Result.failure(RuntimeException("boom"))
        val controller = harness.controller()
        controller.requestOpenFile("m1")
        assertEquals("DOWNLOAD_FAILED", harness.uiState.value.groupEncryptionWarning)
        assertTrue(harness.uiState.value.downloadingFileMessageIds.isEmpty())
    }

    @Test
    fun requestOpenFileIgnoresNonFileMessage() {
        val message = imageMessage("m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.controller().requestOpenFile("m1")
        assertTrue(harness.ensureCalls.isEmpty())
        assertNull(harness.uiState.value.fileReadyToOpenUri)
    }

    @Test
    fun requestOpenFileIgnoresAlreadyDownloading() {
        val message = fileMessage("m1")
        val harness = Harness(state(messages = listOf(message), downloading = setOf("m1")), dispatcher)
        harness.controller().requestOpenFile("m1")
        assertTrue(harness.ensureCalls.isEmpty())
    }

    @Test
    fun requestMediaAttachmentDownloadsReliableType() {
        val message = imageMessage("m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.controller().requestMediaAttachment("m1")
        assertEquals(listOf(message), harness.ensureCalls)
        assertTrue(harness.uiState.value.downloadingFileMessageIds.isEmpty())
    }

    @Test
    fun requestMediaAttachmentFailureMarksMediaFailure() {
        val message = imageMessage("m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.ensureResult = Result.failure(RuntimeException("boom"))
        harness.controller().requestMediaAttachment("m1")
        assertEquals("DOWNLOAD_FAILED", harness.uiState.value.groupEncryptionWarning)
        assertTrue("m1" in harness.uiState.value.mediaDownloadErrorMessageIds)
    }

    @Test
    fun requestMediaAttachmentIgnoresUnreliableType() {
        val message = Message(id = "m1", chatId = "c1", senderId = "u2", content = "hi", type = MessageType.TEXT)
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.controller().requestMediaAttachment("m1")
        assertTrue(harness.ensureCalls.isEmpty())
    }

    @Test
    fun consumeFileReadyToOpenClearsUri() {
        val harness = Harness(state().copy(fileReadyToOpenUri = "file:///local/m1"), dispatcher)
        harness.controller().consumeFileReadyToOpen()
        assertNull(harness.uiState.value.fileReadyToOpenUri)
    }

    @Test
    fun pauseFileTransferCallsIntentForQueuedState() {
        val harness = Harness(
            state(fileTransferStates = mapOf("m1" to AttachmentTransferState.QUEUED)),
            dispatcher,
        )
        harness.controller().pauseFileTransfer("m1")
        assertEquals(listOf("m1"), harness.intent.pauseCalls)
        assertNull(harness.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun pauseFileTransferWarnsWhenIntentRefuses() {
        val harness = Harness(
            state(fileTransferStates = mapOf("m1" to AttachmentTransferState.UPLOADING)),
            dispatcher,
        )
        harness.intent.pauseResult = false
        harness.controller().pauseFileTransfer("m1")
        assertEquals("CONTROL_FAILED", harness.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun pauseFileTransferIgnoresNonPausableState() {
        val harness = Harness(
            state(fileTransferStates = mapOf("m1" to AttachmentTransferState.READY)),
            dispatcher,
        )
        harness.controller().pauseFileTransfer("m1")
        assertTrue(harness.intent.pauseCalls.isEmpty())
    }

    @Test
    fun resumeFileTransferMarksSendingAndRecoversOnRefusal() {
        val message = imageMessage("m1")
        val harness = Harness(state(messages = listOf(message)), dispatcher)
        harness.intent.resumeResult = false
        harness.controller().resumeFileTransfer("m1")
        assertEquals(MessageStatus.FAILED, harness.uiState.value.messages.first().status)
        assertEquals(
            listOf("m1" to MessageStatus.SENDING, "m1" to MessageStatus.FAILED),
            harness.statusUpdates,
        )
        assertEquals("SOURCE_MISSING", harness.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun cancelPreparingTransferRemovesMessageAndReleases() {
        val message = fileMessage("m1")
        val job = Job()
        val harness = Harness(
            state(messages = listOf(message), preparing = setOf("m1")),
            dispatcher,
        )
        harness.preparationJobs["m1"] = job
        harness.controller().cancelFileTransfer("m1")
        assertTrue(harness.uiState.value.messages.isEmpty())
        assertTrue(job.isCancelled)
        assertEquals(listOf("m1"), harness.intent.cancelCalls)
        assertEquals(listOf(message.parsedContent()), harness.releasedPermissions)
        assertEquals(listOf("m1"), harness.deletedMedia)
        assertEquals(listOf("m1"), harness.deletedMessages)
    }

    @Test
    fun cancelFileTransferIgnoresReadyState() {
        val message = fileMessage("m1")
        val harness = Harness(
            state(
                messages = listOf(message),
                fileTransferStates = mapOf("m1" to AttachmentTransferState.READY),
            ),
            dispatcher,
        )
        harness.controller().cancelFileTransfer("m1")
        assertEquals(listOf(message), harness.uiState.value.messages)
        assertTrue(harness.intent.cancelCalls.isEmpty())
        assertTrue(harness.deletedMessages.isEmpty())
    }

    @Test
    fun retryAndCancelAllGuardBlankChatId() {
        val harness = Harness(state(), dispatcher)
        harness.activeChatId = ""
        val controller = harness.controller()
        controller.retryAllAttachmentTransfers()
        controller.cancelAllAttachmentTransfers()
        assertTrue(harness.retryCalls.isEmpty())
        assertTrue(harness.cancelAllCalls.isEmpty())
        harness.activeChatId = "c9"
        controller.retryAllAttachmentTransfers()
        controller.cancelAllAttachmentTransfers()
        assertEquals(listOf("c9"), harness.retryCalls)
        assertEquals(listOf("c9"), harness.cancelAllCalls)
    }

    @Test
    fun updateFileTransferProgressWritesSegmentProgress() {
        val harness = Harness(state(), dispatcher)
        harness.controller().updateFileTransferProgress("m1", 50L, 100L, 0f, 1f)
        assertEquals(0.5f, harness.uiState.value.fileTransferProgress["m1"])
    }

    @Test
    fun attachmentErrorTextMapsEachKind() {
        val harness = Harness(state(), dispatcher)
        val controller = harness.controller()
        assertEquals("FILE_TOO_LARGE", controller.attachmentErrorText(AttachmentTooLargeException(), "fallback"))
        assertEquals(
            "REFERENCE_INVALID",
            controller.attachmentErrorText(AttachmentReferenceInvalidException(), "fallback"),
        )
        assertEquals(
            "INTEGRITY_FAILED",
            controller.attachmentErrorText(
                AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED),
                "fallback",
            ),
        )
        assertEquals(
            "CONTENT_MISMATCH",
            controller.attachmentErrorText(
                AttachmentCryptoException(AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT),
                "fallback",
            ),
        )
        assertEquals(
            "quota exceeded",
            controller.attachmentErrorText(
                ApiException(kind = ApiFailureKind.NETWORK, serverMessage = "quota exceeded"),
                "fallback",
            ),
        )
        assertEquals("fallback", controller.attachmentErrorText(RuntimeException("boom"), "fallback"))
    }

    @Test
    fun cleanupAttachmentForMessageGuardsBlankOwnerAndSwallowsErrors() = runTest {
        val harness = Harness(state(), dispatcher)
        val controller = harness.controller()
        harness.currentUserId = ""
        controller.cleanupAttachmentForMessage("m1")
        assertTrue(harness.cleanupCalls.isEmpty())
        harness.currentUserId = "owner-1"
        controller.cleanupAttachmentForMessage("m1")
        assertEquals(listOf("m1" to "owner-1"), harness.cleanupCalls)
        harness.cleanupThrows = RuntimeException("dao down")
        controller.cleanupAttachmentForMessage("m2")
        assertEquals(1, harness.warnings.size)
        assertTrue(harness.warnings.first().first.contains("m2"))
    }
}
