package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiRequestGenerationGate
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.ConversationLocalStateBackend
import com.maodouchat.core.model.ConversationLocalStateCoordinator
import com.maodouchat.core.model.GroupMessagingCoordinator
import com.maodouchat.core.model.GroupMessagingSession
import com.maodouchat.core.model.User
import com.maodouchat.core.model.WebSocketEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGroupRevisionHandlerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val session = ConversationLocalCleanupSession("owner-1", 1L)

    private class FakeBackend(
        var failTombstone: Boolean = false,
    ) : ConversationLocalStateBackend {
        var tombstoneCalls = 0
        var deleteConversationCalls = 0

        override suspend fun messageIds(chatId: String): List<String> = emptyList()
        override suspend fun tombstoneMessages(ownerUserId: String, chatId: String) {
            tombstoneCalls += 1
            if (failTombstone) error("tombstone boom")
        }
        override suspend fun cancelAttachments(chatId: String) = Unit
        override suspend fun cancelScheduledMessages(ownerUserId: String, chatId: String) = Unit
        override suspend fun cancelReminders(ownerUserId: String, chatId: String) = Unit
        override suspend fun deleteAiTasks(chatId: String) = Unit
        override suspend fun deleteAiOperations(ownerUserId: String, chatId: String) = Unit
        override suspend fun deleteAiSummaries(chatId: String) = Unit
        override suspend fun deleteDraft(ownerUserId: String, chatId: String) = Unit
        override suspend fun deleteLock(chatId: String) = Unit
        override suspend fun clearLockSession(chatId: String) = Unit
        override suspend fun deleteSecretTtl(chatId: String) = Unit
        override suspend fun deactivateSecretSession(chatId: String) = Unit
        override suspend fun deleteSenderKeyRetry(ownerUserId: String, chatId: String) = Unit
        override suspend fun invalidateSenderKey(chatId: String) = Unit
        override suspend fun clearMessagingV2State(
            session: ConversationLocalCleanupSession,
            chatId: String,
            serverParticipantStateDeleted: Boolean,
        ) = Unit
        override suspend fun clearAttachmentWire(ownerUserId: String, chatId: String) = Unit
        override suspend fun deleteMedia(messageId: String) = Unit
        override suspend fun deleteSearchIndex(chatId: String) = Unit
        override suspend fun clearSyncCursors(chatId: String) = Unit
        override suspend fun removeNotificationItems(chatId: String) = Unit
        override suspend fun cancelMessageNotification(chatId: String) = Unit
        override suspend fun cancelAiReminders(chatId: String) = Unit
        override suspend fun deleteMessages(chatId: String) = Unit
        override suspend fun resetConversationPreview(chatId: String) = Unit
        override suspend fun deleteConversation(chatId: String) {
            deleteConversationCalls += 1
        }
    }

    private inner class Harness(
        backend: FakeBackend,
        ownerId: String = "owner-1",
        var mayContinue: Boolean = true,
        var backendSession: ConversationLocalCleanupSession? = session,
    ) {
        var state = ChatDetailUiState(
            contact = User(id = "u2", name = "U2"),
            chat = Chat(id = "c1", participants = listOf(User(id = "owner-1", name = "Me")), isGroup = true, memberRevision = 5L),
            chatIsGroup = true,
        )
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        val invalidatedEpochs = mutableListOf<Triple<String, String, Long?>>()
        val invalidatedLocal = mutableListOf<String>()
        var clearTypingCalls = 0
        var reloadCalls = 0
        val refreshedRoles = mutableListOf<String>()
        val gates = List(5) { AiRequestGenerationGate() }
        val gateTokens = gates.map { it.next() }
        val searchJob = Job()
        val rewriteJob = Job()
        val operationJobs: MutableMap<String, Job> = mutableMapOf("op-1" to Job())
        val retryJobs: MutableMap<String, Job> = mutableMapOf("r-1" to Job())
        val retryAt = mutableMapOf("r-1" to 100L)

        val handler = ChatGroupRevisionHandler(
            ownerUserId = { ownerId },
            activeChatId = { "c1" },
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            text = { it.name },
            sessionMayContinue = { mayContinue },
            cleanupSessionFor = { ConversationLocalCleanupSession(it, 1L) },
            ioDispatcher = dispatcher,
            logWarning = { message, error -> warnings += message to error },
            conversationLocalStateCoordinator = ConversationLocalStateCoordinator(
                currentSession = { backendSession },
                backend = backend,
            ),
            clearRemoteTyping = { clearTypingCalls += 1 },
            semanticSearchGate = gates[0],
            aiRewriteGate = gates[1],
            aiReplyGate = gates[2],
            groupAiGate = gates[3],
            manualSummaryGate = gates[4],
            semanticSearchJob = { searchJob },
            aiRewriteStreamJob = { rewriteJob },
            aiReplyStreamJob = { null },
            groupAiJob = { null },
            manualSummaryJob = { null },
            unreadSummaryJob = { null },
            aiOperationJobs = operationJobs,
            aiAutoRetryJobs = retryJobs,
            aiAutoRetryAt = retryAt,
            groupMessagingCoordinator = GroupMessagingCoordinator(
                currentSession = { GroupMessagingSession(ownerUserId = "owner-1", authToken = "t", deviceId = 1) },
                invalidatePreparedEpoch = { owner, chat, rev, _ -> invalidatedEpochs += Triple(owner, chat, rev) },
                invalidateLocalSenderKey = { invalidatedLocal += it },
                reconcileAttachments = { _, _ -> },
                ensureCoverageNow = { _, _ -> },
                redistributeCoverageNow = { _ -> true },
                fetchCoverageStatus = { _, _, _, _ -> error("unused") },
                hasLocalSenderKeyMaterial = { _, _ -> false },
                enqueueCoverageRetryCommand = { _, _, _ -> },
            ),
            reloadChat = { reloadCalls += 1 },
            refreshMyMemberRole = { refreshedRoles += it },
        )
    }

    private fun event(
        chatId: String = "c1",
        memberRevision: Long = 6L,
        reason: String = "GROUP_RENAMED",
        targetUserId: String? = null,
    ) = WebSocketEvent.GroupRevisionChanged(
        chatId = chatId,
        memberRevision = memberRevision,
        reason = reason,
        targetUserId = targetUserId,
    )

    @Test
    fun blankOwnerReturnsEarly() = runTest(dispatcher) {
        val h = Harness(FakeBackend(), ownerId = "")
        h.handler.handleGroupRevisionChanged(event())
        assertNull(h.state.groupEncryptionWarning)
        assertEquals(0, h.reloadCalls)
        assertTrue(h.refreshedRoles.isEmpty())
    }

    @Test
    fun meOwnerReturnsEarly() = runTest(dispatcher) {
        val h = Harness(FakeBackend(), ownerId = "me")
        h.handler.handleGroupRevisionChanged(event())
        assertNull(h.state.groupEncryptionWarning)
        assertEquals(0, h.reloadCalls)
    }

    @Test
    fun sessionMayNotContinueReturnsEarly() = runTest(dispatcher) {
        val h = Harness(FakeBackend(), mayContinue = false)
        h.handler.handleGroupRevisionChanged(event())
        assertNull(h.state.groupEncryptionWarning)
        assertEquals(0, h.reloadCalls)
        assertTrue(h.invalidatedEpochs.isEmpty())
    }

    @Test
    fun otherChatEventIgnored() = runTest(dispatcher) {
        val h = Harness(FakeBackend())
        h.handler.handleGroupRevisionChanged(event(chatId = "c2"))
        assertNull(h.state.groupEncryptionWarning)
        assertEquals(0, h.reloadCalls)
        assertTrue(h.invalidatedEpochs.isEmpty())
    }

    @Test
    fun renameWritesWarningInvalidatesKeyAndReloads() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(backend)
        h.handler.handleGroupRevisionChanged(event())
        assertEquals("GROUP_NAME_UPDATED", h.state.groupEncryptionWarning)
        assertEquals(listOf<Triple<String, String, Long?>>(Triple("owner-1", "c1", 6L)), h.invalidatedEpochs)
        assertEquals(listOf("c1"), h.invalidatedLocal)
        assertEquals(1, h.reloadCalls)
        assertEquals(listOf("owner-1"), h.refreshedRoles)
        assertEquals(0, backend.tombstoneCalls)
    }

    @Test
    fun keyInvalidationSkippedWhenRevisionNotNewer() = runTest(dispatcher) {
        val h = Harness(FakeBackend())
        h.handler.handleGroupRevisionChanged(event(memberRevision = 5L))
        assertEquals("GROUP_NAME_UPDATED", h.state.groupEncryptionWarning)
        assertTrue(h.invalidatedEpochs.isEmpty())
        assertEquals(1, h.reloadCalls)
    }

    @Test
    fun memberAddedMapsToMembersChanged() = runTest(dispatcher) {
        val h = Harness(FakeBackend())
        h.handler.handleGroupRevisionChanged(event(reason = "MEMBER_ADDED"))
        assertEquals("GROUP_MEMBERS_CHANGED_KEY", h.state.groupEncryptionWarning)
    }

    @Test
    fun muteUpdatedMapsToMuteWarning() = runTest(dispatcher) {
        val h = Harness(FakeBackend())
        h.handler.handleGroupRevisionChanged(event(reason = "MUTE_UPDATED"))
        assertEquals("GROUP_MUTE_UPDATED", h.state.groupEncryptionWarning)
    }

    @Test
    fun unknownReasonFallsBackToInfo() = runTest(dispatcher) {
        val h = Harness(FakeBackend())
        h.handler.handleGroupRevisionChanged(event(reason = "SOMETHING_ELSE"))
        assertEquals("GROUP_INFO_UPDATED", h.state.groupEncryptionWarning)
    }

    @Test
    fun removedFromGroupClearsStateAndCleansUp() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(backend)
        h.handler.handleGroupRevisionChanged(event(reason = "MEMBER_REMOVED", targetUserId = "owner-1"))
        assertNull(h.state.chat)
        assertFalse(h.state.chatIsGroup)
        assertTrue(h.state.messages.isEmpty())
        assertEquals("LEFT_GROUP_KEY_CLEARED", h.state.groupEncryptionWarning)
        assertEquals(1, h.clearTypingCalls)
        assertTrue(h.gateTokens.zip(h.gates).all { (token, gate) -> !gate.isCurrent(token) })
        assertTrue(h.searchJob.isCancelled)
        assertTrue(h.rewriteJob.isCancelled)
        assertTrue(h.operationJobs.isEmpty())
        assertTrue(h.retryJobs.isEmpty())
        assertTrue(h.retryAt.isEmpty())
        assertEquals(1, backend.tombstoneCalls)
        assertEquals(1, backend.deleteConversationCalls)
        assertTrue(h.warnings.isEmpty())
        assertEquals(0, h.reloadCalls)
        assertTrue(h.refreshedRoles.isEmpty())
    }

    @Test
    fun removedFromGroupCleanupFailureWarnsButStillClears() = runTest(dispatcher) {
        val backend = FakeBackend(failTombstone = true)
        val h = Harness(backend)
        h.handler.handleGroupRevisionChanged(event(reason = "MEMBER_LEFT", targetUserId = "owner-1"))
        assertNull(h.state.chat)
        assertEquals("LEFT_GROUP_KEY_CLEARED", h.state.groupEncryptionWarning)
        assertEquals(1, h.warnings.size)
        assertTrue(h.warnings[0].first.startsWith("group removal cleanup failed at TOMBSTONE_MESSAGES for c1"))
        assertTrue(h.warnings[0].second is IllegalStateException)
    }

    @Test
    fun removedFromGroupSessionChangedStopsBeforeClear() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(backend, backendSession = ConversationLocalCleanupSession("owner-1", 2L))
        h.handler.handleGroupRevisionChanged(event(reason = "MEMBER_REMOVED", targetUserId = "owner-1"))
        assertEquals("c1", h.state.chat?.id)
        assertNull(h.state.groupEncryptionWarning)
        assertEquals(0, backend.deleteConversationCalls)
    }
}
