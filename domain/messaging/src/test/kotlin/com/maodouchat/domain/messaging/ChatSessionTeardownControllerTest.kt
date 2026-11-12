package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Lease
import com.maodouchat.core.model.User
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTeardownControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))
    private val groupChat = Chat(id = "c1", participants = listOf(me, peer), isGroup = true, memberRevision = 7L)

    private inner class Harness(
        initial: ChatDetailUiState,
        private val dispatcher: CoroutineDispatcher,
    ) {
        val uiState = MutableStateFlow(initial)
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)

        var liveLocationFinal: Boolean? = null
        var recordingMeterStopped = false
        var cancelRecordingCalls = 0
        var stopVoicePlaybackCalls = 0
        var realtimeCleared = false
        var aiJobsCancelled = false
        var draftJobCancelled = false
        var readSeenCleared = false
        var pendingWatermark: String? = "m9"
        var markReadJobCancelled = false
        val drafts = mutableListOf<Triple<String, String, String>>()
        val receipts = mutableListOf<Triple<String, String, Long?>>()
        val releasedLeases = mutableListOf<Lease>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        var draftsEnabled = true
        var ownerId = "owner-1"
        var sessionMayContinue = true
        var token: String? = "tok"
        var sessionUserId: String? = "owner-1"
        var enqueueFails = false
        var activeChatId = "c1"

        private val cipherController = ChatSessionCipherController(
            initialChatId = "c1",
            getChatById = { chat },
            acquireOccupancy = { Lease(1) },
            occupyOccupancy = { _, _, _, _ -> },
            releaseOccupancy = { releasedLeases += it },
            currentUserId = { ownerId },
        )

        fun controller() = ChatSessionTeardownController(
            uiState = uiState,
            scope = scope,
            draftsFeatureEnabled = { draftsEnabled },
            cancelRecording = { cancelRecordingCalls++ },
            stopVoicePlayback = { stopVoicePlaybackCalls++ },
            clearRealtime = { realtimeCleared = true },
            cipherController = cipherController,
            getActiveChatId = { activeChatId },
            stopLiveLocationSharing = { final -> liveLocationFinal = final },
            stopRecordingMeter = { recordingMeterStopped = true },
            cancelAiAutoRetryJobs = { aiJobsCancelled = true },
            cancelDraftSaveJob = { draftJobCancelled = true },
            clearReadSeen = { readSeenCleared = true },
            takePendingReadWatermark = { pendingWatermark.also { pendingWatermark = null } },
            cancelMarkReadJob = { markReadJobCancelled = true },
            persistDraft = { owner, chatId, text -> drafts += Triple(owner, chatId, text) },
            ownerUserId = { ownerId },
            sessionMayContinue = { sessionMayContinue },
            sessionToken = { token },
            sessionUserId = { sessionUserId },
            enqueueReadReceipt = { conversationId, throughMessageId, groupRevision ->
                if (enqueueFails) throw RuntimeException("boom")
                receipts += Triple(conversationId, throughMessageId, groupRevision)
            },
            logWarning = { message, error -> warnings += message to error },
        )

        suspend fun settle() {
            scopeJob.children.forEach { it.join() }
        }
    }

    private fun state(
        inputText: String = "",
        voicePreviewPath: String? = null,
        isSecretChat: Boolean? = null,
        chat: Chat? = this.chat,
    ) = ChatDetailUiState(
        contact = peer,
        chat = chat,
        inputText = inputText,
        voicePreviewPath = voicePreviewPath,
        isSecretChat = isSecretChat,
    )

    @Test
    fun onClearedStopsMediaAndJobs() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.liveLocationFinal == true)
        assertTrue(harness.recordingMeterStopped)
        assertEquals(1, harness.cancelRecordingCalls)
        assertEquals(1, harness.stopVoicePlaybackCalls)
        assertTrue(harness.aiJobsCancelled)
        assertTrue(harness.draftJobCancelled)
        assertTrue(harness.realtimeCleared)
    }

    @Test
    fun onClearedDeletesVoicePreviewFile() = runTest(dispatcher) {
        val preview = File.createTempFile("preview", ".tmp")
        preview.writeText("voice")
        val harness = Harness(state(voicePreviewPath = preview.absolutePath), dispatcher)
        harness.controller().onCleared()
        harness.settle()
        assertFalse(preview.exists())
    }

    @Test
    fun persistDraftSkippedWhenDraftsDisabled() = runTest(dispatcher) {
        val harness = Harness(state(inputText = "hello"), dispatcher).apply { draftsEnabled = false }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.drafts.isEmpty())
    }

    @Test
    fun persistDraftSkippedWhenOwnerBlank() = runTest(dispatcher) {
        val harness = Harness(state(inputText = "hello"), dispatcher).apply { ownerId = "" }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.drafts.isEmpty())
    }

    @Test
    fun persistDraftSkippedWhenChatBlank() = runTest(dispatcher) {
        val harness = Harness(state(inputText = "hello"), dispatcher).apply { activeChatId = "" }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.drafts.isEmpty())
        assertTrue(harness.receipts.isEmpty())
    }

    @Test
    fun persistDraftRunsWhenEnabledAndGatePasses() = runTest(dispatcher) {
        val harness = Harness(state(inputText = "hello"), dispatcher)
        harness.controller().onCleared()
        harness.settle()
        assertEquals(listOf(Triple("owner-1", "c1", "hello")), harness.drafts)
    }

    @Test
    fun persistDraftSkippedWhenGateRejects() = runTest(dispatcher) {
        val harness = Harness(state(inputText = "hello"), dispatcher).apply { sessionMayContinue = false }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.drafts.isEmpty())
    }

    @Test
    fun finalReceiptEnqueuedWhenValid() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().onCleared()
        harness.settle()
        assertEquals(listOf<Triple<String, String, Long?>>(Triple("c1", "m9", null)), harness.receipts)
        assertEquals(1, harness.releasedLeases.size)
        assertTrue(harness.readSeenCleared)
        assertTrue(harness.markReadJobCancelled)
        assertNull(harness.pendingWatermark)
    }

    @Test
    fun finalReceiptCarriesGroupRevision() = runTest(dispatcher) {
        val harness = Harness(state(chat = groupChat), dispatcher)
        harness.controller().onCleared()
        harness.settle()
        assertEquals(listOf<Triple<String, String, Long?>>(Triple("c1", "m9", 7L)), harness.receipts)
    }

    @Test
    fun finalReceiptSkippedWhenWatermarkNull() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { pendingWatermark = null }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.receipts.isEmpty())
        assertEquals(1, harness.releasedLeases.size)
    }

    @Test
    fun finalReceiptSkippedWhenTokenBlank() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { token = null }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.receipts.isEmpty())
    }

    @Test
    fun finalReceiptSkippedForSecretChat() = runTest(dispatcher) {
        val harness = Harness(state(isSecretChat = true), dispatcher)
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.receipts.isEmpty())
    }

    @Test
    fun finalReceiptFailureLogsWarning() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { enqueueFails = true }
        harness.controller().onCleared()
        harness.settle()
        assertTrue(harness.receipts.isEmpty())
        assertEquals(1, harness.warnings.size)
        assertEquals("final v2 read receipt enqueue failed", harness.warnings[0].first)
    }
}
