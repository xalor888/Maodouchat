package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.ConversationLocalStateBackend
import com.maodouchat.core.model.ConversationLocalStateCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListLocalCleanupCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val session = ConversationLocalCleanupSession("me", 1L)

    private class FakeBackend(
        var failTombstone: Boolean = false,
    ) : ConversationLocalStateBackend {
        val draftDeletes = mutableListOf<Pair<String, String>>()
        var tombstoneCalls = 0

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
        override suspend fun deleteDraft(ownerUserId: String, chatId: String) {
            draftDeletes += ownerUserId to chatId
        }
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
        override suspend fun deleteConversation(chatId: String) = Unit
    }

    private inner class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        backend: FakeBackend,
        ownerId: String = "me",
        var deleteDraftError: Throwable? = null,
    ) {
        val warnings = mutableListOf<String>()
        val reloads = mutableListOf<Unit>()
        val draftDeletes = mutableListOf<Pair<String, String>>()
        val coordinator = ChatListLocalCleanupCoordinator(
            scope = scope,
            ownerUserId = { ownerId },
            localStateCoordinator = ConversationLocalStateCoordinator(
                currentSession = { session },
                backend = backend,
            ),
            deleteDraftForChat = { owner, chatId ->
                deleteDraftError?.let { throw it }
                draftDeletes += owner to chatId
            },
            reloadChats = { reloads += Unit },
            cleanupSessionFor = { ConversationLocalCleanupSession(it, 1L) },
            logWarning = { message, _ -> warnings += message },
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun clearLocalChatHistoryBlankChatIsNoOp() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(this, backend)

        h.coordinator.clearLocalChatHistory("  ")
        advanceUntilIdle()

        assertTrue(h.reloads.isEmpty())
        assertEquals(0, backend.tombstoneCalls)
    }

    @Test
    fun clearLocalChatHistorySuccessReloads() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(this, backend)

        h.coordinator.clearLocalChatHistory("c1")
        advanceUntilIdle()

        assertEquals(1, h.reloads.size)
        assertTrue(h.warnings.isEmpty())
        assertEquals(1, backend.tombstoneCalls)
    }

    @Test
    fun clearLocalChatHistoryFailureWarnsButStillReloads() = runTest(dispatcher) {
        val backend = FakeBackend(failTombstone = true)
        val h = Harness(this, backend)

        h.coordinator.clearLocalChatHistory("c1")
        advanceUntilIdle()

        assertEquals(1, h.warnings.size)
        assertTrue(h.warnings.single().contains("c1"))
        assertEquals(1, h.reloads.size)
    }

    @Test
    fun cleanupLocalChatDeletesDraft() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(this, backend)

        h.coordinator.cleanupLocalChat("c1", session)

        assertEquals(listOf("me" to "c1"), backend.draftDeletes)
        assertTrue(h.warnings.isEmpty())
    }

    @Test
    fun clearChatDraftDeletesDraft() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(this, backend)

        h.coordinator.clearChatDraft("c1")
        advanceUntilIdle()

        assertEquals(listOf("me" to "c1"), h.draftDeletes)
    }

    @Test
    fun clearChatDraftSwallowsError() = runTest(dispatcher) {
        val backend = FakeBackend()
        val h = Harness(this, backend, deleteDraftError = IllegalStateException("x"))

        h.coordinator.clearChatDraft("c1")
        advanceUntilIdle()

        assertTrue(h.draftDeletes.isEmpty())
    }
}
