package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ConversationMessageMutationCoordinator
import com.maodouchat.core.model.ConversationReactionCoordinator
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMutationProjection
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2EventOutbox
import com.maodouchat.core.model.MessagingV2MutationFacade
import com.maodouchat.core.model.PinnedMessageDto
import com.maodouchat.core.model.User
import com.maodouchat.core.model.mergeMessageVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageMutationControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    private fun message(
        id: String = "m1",
        senderId: String = "owner-1",
        content: String = "body",
        timestamp: Long = System.currentTimeMillis(),
    ) = Message(
        id = id,
        chatId = "c1",
        senderId = senderId,
        content = content,
        type = MessageType.TEXT,
        timestamp = timestamp,
    )

    private class Harness(
        var current: ChatDetailUiState,
        private val dispatcher: CoroutineDispatcher,
        val attempted: MutableList<String> = mutableListOf(),
        val projected: MutableList<MessageMutationProjection> = mutableListOf(),
        val warnings: MutableList<Pair<String, Throwable?>> = mutableListOf(),
        var failTargets: Set<String> = emptySet(),
    ) {
        // facade 内的 Dispatchers.IO 跳转不在测试调度器上：测试持 scope 的 Job，
        // 用 join 等 launched 协程真正跑完再断言（不用 advanceUntilIdle 猜）。
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)

        private fun facade() = MessagingV2MutationFacade(
            eventOutbox = MessagingV2EventOutbox { _, event, _ ->
                attempted += event.targetMessageId
                if (event.targetMessageId in failTargets) throw RuntimeException("boom")
            },
            persistDeleted = {},
            persistRevoked = { _, _ -> },
            persistEdited = { it },
            persistReaction = { _, _, _, _ -> },
            indexMessage = {},
            cleanupAttachment = {},
            refreshConversationPreview = {},
            isOwnerSessionCurrent = { it == "owner-1" },
        )

        private fun applyProjection(projection: MessageMutationProjection) {
            projected += projection
            current = when (projection) {
                is MessageMutationProjection.Remove -> current.copy(
                    messages = current.messages.filterNot { it.id == projection.messageId },
                )
                // Set 与生产 ChatTimelineStateController.applyMutation 同语义：
                // 已存在则原地替换，不存在则按时间线合并插入（回滚时被乐观删除的消息要回来）。
                is MessageMutationProjection.Set -> current.copy(
                    messages = if (current.messages.any { it.id == projection.message.id }) {
                        current.messages.map {
                            if (it.id == projection.message.id) projection.message else it
                        }
                    } else {
                        mergeMessageVersions(current.messages, listOf(projection.message))
                    },
                )
            }
        }

        suspend fun settle() {
            scopeJob.children.forEach { it.join() }
        }

        fun controller(
            token: String = "tok",
            revokeEnabled: Boolean = true,
            editEnabled: Boolean = true,
            requireReactions: Boolean = true,
        ): ChatMessageMutationController {
            val mutationCoordinator = ConversationMessageMutationCoordinator(facade())
            val reactionCoordinator = ConversationReactionCoordinator(facade())
            return ChatMessageMutationController(
                scope = scope,
                ownerUserId = { "owner-1" },
                token = { token },
                currentState = { current },
                updateState = { transform -> current = transform(current) },
                text = { message -> "text:${message.name}" },
                currentGroupRevision = { null },
                composeContentWithMeta = { text, _ -> text },
                requireReactions = { requireReactions },
                projectMessageMutation = ::applyProjection,
                mutationCoordinator = mutationCoordinator,
                reactionCoordinator = reactionCoordinator,
                messageRevokeEnabled = { revokeEnabled },
                messageEditEnabled = { editEnabled },
                logWarning = { message, error -> warnings += message to error },
            )
        }
    }

    private fun state(messages: List<Message> = listOf(message())) = ChatDetailUiState(
        contact = peer,
        chat = chat,
        messages = messages,
        pinnedMessages = messages.map {
            PinnedMessageDto(chatId = "c1", messageId = it.id, pinnedBy = "owner-1", pinnedAt = 1L)
        },
    )

    @Test
    fun deleteDisabledByFlagWritesWarning() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller(revokeEnabled = false).deleteMessage("m1")

        assertEquals("text:FEATURE_DISABLED", harness.current.groupEncryptionWarning)
        assertTrue(harness.attempted.isEmpty())
        assertEquals(1, harness.current.messages.size)
    }

    @Test
    fun deleteBlankTokenWritesSessionExpired() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller(token = "").deleteMessage("m1")

        assertEquals("text:SESSION_EXPIRED", harness.current.groupEncryptionWarning)
        assertTrue(harness.attempted.isEmpty())
    }

    @Test
    fun deleteHappyPathRemovesMessageAndPinnedReference() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().deleteMessage("m1")
        harness.settle()

        assertEquals(listOf("m1"), harness.attempted)
        assertEquals(listOf<MessageMutationProjection>(MessageMutationProjection.Remove("m1")), harness.projected)
        assertTrue(harness.current.messages.isEmpty())
        assertTrue(harness.current.pinnedMessages.isEmpty())
        assertNull(harness.current.groupEncryptionWarning)
    }

    @Test
    fun deleteFailureWritesErrorAndRollsBack() = runTest(dispatcher) {
        val original = message()
        val harness = Harness(state(listOf(original)), dispatcher).apply { failTargets = setOf("m1") }
        harness.controller().deleteMessage("m1")
        harness.settle()

        assertEquals("boom", harness.current.groupEncryptionWarning)
        assertEquals(
            listOf(
                MessageMutationProjection.Remove("m1"),
                MessageMutationProjection.Set(original),
            ),
            harness.projected,
        )
        assertEquals(1, harness.current.messages.size)
    }

    @Test
    fun batchDeleteContinuesAfterSingleFailure() = runTest(dispatcher) {
        val m2 = message(id = "m2")
        val harness = Harness(state(listOf(message(), m2)), dispatcher).apply { failTargets = setOf("m1") }
        harness.controller().deleteMessagesBatch(listOf("m1", "m2"))
        harness.settle()

        assertEquals(listOf("m1", "m2"), harness.attempted)
        // m1 失败已回滚（仍在列表里），m2 成功删掉：剩下的应是 m1，不是 m2。
        assertEquals(listOf("m1"), harness.current.messages.map { it.id })
        assertEquals("boom", harness.current.groupEncryptionWarning)
    }

    @Test
    fun revokeHappyPathReplacesWithPlaceholder() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().revokeMessage("m1")
        harness.settle()

        assertEquals(listOf("m1"), harness.attempted)
        val revoked = harness.current.messages.single()
        assertEquals(MessageType.REVOKED, revoked.type)
        assertEquals("text:MESSAGE_REVOKED_PLACEHOLDER", revoked.content)
        assertTrue(harness.current.pinnedMessages.isEmpty())
    }

    @Test
    fun editBlankTextWritesEmptyWarning() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().editTextMessage("m1", "   ")

        assertEquals("text:EDIT_EMPTY", harness.current.groupEncryptionWarning)
        assertTrue(harness.attempted.isEmpty())
    }

    @Test
    fun editNotOwnerWritesNotAllowed() = runTest(dispatcher) {
        val harness = Harness(state(listOf(message(senderId = "u2"))), dispatcher)
        harness.controller().editTextMessage("m1", "new text")

        assertEquals("text:EDIT_NOT_ALLOWED", harness.current.groupEncryptionWarning)
        assertTrue(harness.attempted.isEmpty())
    }

    @Test
    fun editTooOldWritesNotAllowed() = runTest(dispatcher) {
        val harness = Harness(
            state(listOf(message(timestamp = System.currentTimeMillis() - 300_001))),
            dispatcher,
        )
        harness.controller().editTextMessage("m1", "new text")

        assertEquals("text:EDIT_NOT_ALLOWED", harness.current.groupEncryptionWarning)
        assertTrue(harness.attempted.isEmpty())
    }

    @Test
    fun editHappyPathUpdatesContent() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().editTextMessage("m1", "  new text  ")
        harness.settle()

        assertEquals(listOf("m1"), harness.attempted)
        assertEquals("new text", harness.current.messages.single().content)
        assertNull(harness.current.groupEncryptionWarning)
    }

    @Test
    fun reactionDisabledIsNoop() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller(requireReactions = false).setMessageReaction("m1", "👍")
        harness.settle()

        assertTrue(harness.attempted.isEmpty())
        assertTrue(harness.current.messages.single().reactions.isEmpty())
    }

    @Test
    fun reactionHappyPathTogglesEmoji() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().setMessageReaction("m1", "👍")
        harness.settle()

        assertEquals(listOf("m1"), harness.attempted)
        assertTrue(
            harness.current.messages.single().reactions.any {
                it.userId == "owner-1" && it.emoji == "👍"
            },
        )
    }
}
