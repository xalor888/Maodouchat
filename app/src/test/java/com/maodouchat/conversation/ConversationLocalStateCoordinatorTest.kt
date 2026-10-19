package com.maodouchat.conversation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationLocalStateCoordinatorTest {
    @Test
    fun `history cleanup preserves conversation metadata and resets preview`() = runTest {
        val session = session()
        val backend = RecordingBackend(messageIds = listOf("m1", "m2"))
        val coordinator = ConversationLocalStateCoordinator({ session }, backend)

        val report = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session,
            mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
        )

        assertTrue(report.completed)
        assertTrue(report.failures.isEmpty())
        assertEquals(
            listOf(
                "messageIds",
                "tombstoneMessages",
                "cancelAttachments",
                "cancelScheduledMessages:$OWNER",
                "clearMessagingV2State:false",
                "clearAttachmentWire:$OWNER",
                "deleteMedia:m1",
                "deleteMedia:m2",
                "deleteSearchIndex",
                "removeNotificationItems",
                "cancelMessageNotification",
                "deleteMessages",
                "resetConversationPreview",
            ),
            backend.calls,
        )
        assertFalse("deleteDraft" in backend.calls)
        assertFalse("deleteConversation" in backend.calls)
    }

    @Test
    fun `full deletion continues after isolated cache failure`() = runTest {
        val session = session()
        val backend = RecordingBackend(
            messageIds = listOf("m1"),
            failStep = "deleteSearchIndex",
        )
        val coordinator = ConversationLocalStateCoordinator({ session }, backend)

        val report = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session,
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )

        assertTrue(report.completed)
        assertEquals(
            listOf(ConversationLocalCleanupStep.DELETE_SEARCH_INDEX),
            report.failures.map { it.step },
        )
        assertTrue("deleteMessages" in backend.calls)
        assertTrue("deleteConversation" in backend.calls)
        assertTrue("cancelReminders:$OWNER" in backend.calls)
        assertTrue("invalidateSenderKey" in backend.calls)
        assertTrue("clearMessagingV2State:true" in backend.calls)
    }

    @Test
    fun `account switch stops cleanup before touching later state`() = runTest {
        val session = session()
        var current: ConversationLocalCleanupSession? = session
        val backend = RecordingBackend(
            afterCall = { call ->
                if (call == "cancelAttachments") {
                    current = ConversationLocalCleanupSession("bob", session.generation + 1)
                }
            },
        )
        val coordinator = ConversationLocalStateCoordinator({ current }, backend)

        val report = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session,
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )

        assertTrue(report.sessionChanged)
        assertFalse(report.completed)
        assertEquals(listOf("messageIds", "tombstoneMessages", "cancelAttachments"), backend.calls)
    }

    @Test
    fun `history tombstones are durable before attachment cancellation starts`() = runTest {
        val session = session()
        val backend = RecordingBackend(messageIds = listOf("m1"))

        ConversationLocalStateCoordinator({ session }, backend).cleanup(
            chatId = CHAT_ID,
            expectedSession = session,
            mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
        )

        assertTrue(backend.calls.indexOf("tombstoneMessages") < backend.calls.indexOf("cancelAttachments"))
    }

    @Test
    fun `forgotten lock mode removes lock but preserves conversation state`() = runTest {
        val session = session()
        val backend = RecordingBackend()
        val coordinator = ConversationLocalStateCoordinator({ session }, backend)

        coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session,
            mode = ConversationLocalCleanupMode.CLEAR_HISTORY_AND_LOCK,
        )

        assertTrue("deleteLock" in backend.calls)
        assertTrue("clearLockSession" in backend.calls)
        assertTrue("resetConversationPreview" in backend.calls)
        assertFalse("deleteDraft" in backend.calls)
        assertFalse("deleteConversation" in backend.calls)
    }

    @Test
    fun `capture message ids failure skips media deletion but continues remaining steps`() = runTest {
        val backend = RecordingBackend(messageIds = listOf("m1"), failStep = "messageIds")
        val coordinator = ConversationLocalStateCoordinator({ session() }, backend)

        val report = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session(),
            mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
        )

        assertTrue(report.completed)
        assertEquals(
            listOf(ConversationLocalCleanupStep.CAPTURE_MESSAGE_IDS),
            report.failures.map { it.step },
        )
        assertTrue(backend.calls.none { it.startsWith("deleteMedia") })
        assertTrue("deleteMessages" in backend.calls)
        assertTrue("resetConversationPreview" in backend.calls)
    }

    @Test
    fun `full deletion pins exact convergence order`() = runTest {
        val backend = RecordingBackend(messageIds = listOf("m1"))
        val coordinator = ConversationLocalStateCoordinator({ session() }, backend)

        val report = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session(),
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )

        assertTrue(report.completed)
        assertTrue(report.failures.isEmpty())
        assertEquals(
            listOf(
                "messageIds",
                "tombstoneMessages",
                "cancelAttachments",
                "cancelScheduledMessages:$OWNER",
                "cancelReminders:$OWNER",
                "deleteAiTasks",
                "deleteAiOperations:$OWNER",
                "deleteAiSummaries",
                "deleteDraft",
                "deleteLock",
                "clearLockSession",
                "deleteSecretTtl",
                "deactivateSecretSession",
                "deleteSenderKeyRetry:$OWNER",
                "invalidateSenderKey",
                "clearMessagingV2State:true",
                "clearAttachmentWire:$OWNER",
                "deleteMedia:m1",
                "deleteSearchIndex",
                "clearSyncCursors",
                "removeNotificationItems",
                "cancelMessageNotification",
                "cancelAiReminders",
                "deleteMessages",
                "deleteConversation",
            ),
            backend.calls,
        )
    }

    @Test
    fun `session changed before first step leaves all state untouched`() = runTest {
        val backend = RecordingBackend(messageIds = listOf("m1"))
        val coordinator = ConversationLocalStateCoordinator(
            { ConversationLocalCleanupSession("bob", 8L) },
            backend,
        )

        val report = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session(),
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )

        assertTrue(report.sessionChanged)
        assertFalse(report.completed)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `blank chat id and blank owner are rejected before any backend call`() = runTest {
        val backend = RecordingBackend()
        val coordinator = ConversationLocalStateCoordinator({ session() }, backend)

        assertFailsWith<IllegalArgumentException> {
            coordinator.cleanup(
                chatId = " ",
                expectedSession = session(),
                mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.cleanup(
                chatId = CHAT_ID,
                expectedSession = ConversationLocalCleanupSession("", 7L),
                mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
            )
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `repeated cleanup runs both complete without compounding failures`() = runTest {
        val backend = RecordingBackend(messageIds = listOf("m1"))
        val coordinator = ConversationLocalStateCoordinator({ session() }, backend)

        val first = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session(),
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )
        val second = coordinator.cleanup(
            chatId = CHAT_ID,
            expectedSession = session(),
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )

        assertTrue(first.completed)
        assertTrue(second.completed)
        assertTrue(first.failures.isEmpty())
        assertTrue(second.failures.isEmpty())
        assertEquals(2, backend.calls.count { it == "deleteConversation" })
    }

    private class RecordingBackend(
        private val messageIds: List<String> = emptyList(),
        private val failStep: String? = null,
        private val afterCall: (String) -> Unit = {},
    ) : ConversationLocalStateBackend {
        val calls = mutableListOf<String>()

        private fun record(call: String) {
            calls += call
            afterCall(call)
            if (call == failStep) throw IllegalStateException(call)
        }

        override suspend fun messageIds(chatId: String): List<String> {
            record("messageIds")
            return messageIds
        }

        override suspend fun tombstoneMessages(ownerUserId: String, chatId: String) =
            record("tombstoneMessages")

        override suspend fun cancelAttachments(chatId: String) = record("cancelAttachments")
        override suspend fun cancelScheduledMessages(ownerUserId: String, chatId: String) =
            record("cancelScheduledMessages:$ownerUserId")

        override suspend fun cancelReminders(ownerUserId: String, chatId: String) =
            record("cancelReminders:$ownerUserId")

        override suspend fun deleteAiTasks(chatId: String) = record("deleteAiTasks")
        override suspend fun deleteAiOperations(ownerUserId: String, chatId: String) =
            record("deleteAiOperations:$ownerUserId")

        override suspend fun deleteAiSummaries(chatId: String) = record("deleteAiSummaries")
        override suspend fun deleteDraft(ownerUserId: String, chatId: String) = record("deleteDraft")
        override suspend fun deleteLock(chatId: String) = record("deleteLock")
        override suspend fun clearLockSession(chatId: String) = record("clearLockSession")
        override suspend fun deleteSecretTtl(chatId: String) = record("deleteSecretTtl")
        override suspend fun deactivateSecretSession(chatId: String) = record("deactivateSecretSession")
        override suspend fun deleteSenderKeyRetry(ownerUserId: String, chatId: String) =
            record("deleteSenderKeyRetry:$ownerUserId")

        override suspend fun invalidateSenderKey(chatId: String) = record("invalidateSenderKey")
        override suspend fun clearMessagingV2State(
            session: ConversationLocalCleanupSession,
            chatId: String,
            serverParticipantStateDeleted: Boolean,
        ) = record("clearMessagingV2State:$serverParticipantStateDeleted")

        override suspend fun clearAttachmentWire(ownerUserId: String, chatId: String) =
            record("clearAttachmentWire:$ownerUserId")

        override suspend fun deleteMedia(messageId: String) = record("deleteMedia:$messageId")
        override suspend fun deleteSearchIndex(chatId: String) = record("deleteSearchIndex")
        override suspend fun clearSyncCursors(chatId: String) = record("clearSyncCursors")
        override suspend fun removeNotificationItems(chatId: String) = record("removeNotificationItems")
        override suspend fun cancelMessageNotification(chatId: String) = record("cancelMessageNotification")
        override suspend fun cancelAiReminders(chatId: String) = record("cancelAiReminders")
        override suspend fun deleteMessages(chatId: String) = record("deleteMessages")
        override suspend fun resetConversationPreview(chatId: String) = record("resetConversationPreview")
        override suspend fun deleteConversation(chatId: String) = record("deleteConversation")
    }

    private fun session() = ConversationLocalCleanupSession(OWNER, generation = 7L)

    private companion object {
        const val OWNER = "alice"
        const val CHAT_ID = "chat-1"
    }
}
