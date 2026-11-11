package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ConversationMessageMutationCoordinator
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMutationKind
import com.maodouchat.core.model.MessageMutationProjection
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2AuthoritativeMutation
import com.maodouchat.core.model.MessagingV2EventOutbox
import com.maodouchat.core.model.MessagingV2MutationFacade
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageObservationControllerTest {

    // backgroundScope 里的 collect 须立即订阅：默认调度器下 advanceUntilIdle()
    // 不会跑起它，emit/置值会丢给无人订阅的流，改走 Unconfined（仓内同类测试惯例）。
    private val dispatcher = UnconfinedTestDispatcher()

    private val localMessages = MutableStateFlow<List<Message>>(emptyList())
    private val mutationEvents = MutableSharedFlow<MessagingV2AuthoritativeMutation>()
    private var state = ChatDetailUiState()
    private var sessionMayContinue = true
    private val projections = mutableListOf<MessageMutationProjection>()

    private fun coordinator() = ConversationMessageMutationCoordinator(
        MessagingV2MutationFacade(
            eventOutbox = MessagingV2EventOutbox { _, _, _ -> },
            persistDeleted = {},
            persistRevoked = { _, _ -> },
            persistEdited = { null },
            persistReaction = { _, _, _, _ -> },
            indexMessage = {},
            cleanupAttachment = {},
            refreshConversationPreview = {},
            isOwnerSessionCurrent = { true },
        ),
    )

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        chatId: String = "chat",
    ) = ChatMessageObservationController(
        scope = scope,
        ownerUserId = { "user" },
        activeChatId = { "chat" },
        chatId = chatId,
        observeLocalMessages = { localMessages },
        mutationEvents = mutationEvents,
        sessionMayContinue = { sessionMayContinue },
        timelineStateController = ChatTimelineStateController(),
        mutationCoordinator = coordinator(),
        updateState = { transform -> state = transform(state) },
        projectMessageMutation = { projections += it },
    )

    private fun message(id: String, timestamp: Long, type: MessageType = MessageType.TEXT) = Message(
        id = id,
        chatId = "chat",
        senderId = "user",
        content = id,
        type = type,
        timestamp = timestamp,
        status = MessageStatus.SENDING,
    )

    @Test
    fun `local messages merge visible rows and drop SK_DIST`() = runTest(dispatcher) {
        controller(backgroundScope).observeLocalMessages()

        localMessages.value = listOf(message("m1", 1L), message("m2", 2L, MessageType.SK_DIST))
        advanceUntilIdle()

        assertEquals(listOf("m1"), state.messages.map(Message::id))
    }

    @Test
    fun `local messages ignored when session may not continue`() = runTest(dispatcher) {
        sessionMayContinue = false
        controller(backgroundScope).observeLocalMessages()

        localMessages.value = listOf(message("m1", 1L))
        advanceUntilIdle()

        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `local messages ignored for blank chat id`() = runTest(dispatcher) {
        val blank = ChatMessageObservationController(
            scope = backgroundScope,
            ownerUserId = { "user" },
            activeChatId = { "" },
            chatId = "",
            observeLocalMessages = { localMessages },
            mutationEvents = mutationEvents,
            sessionMayContinue = { true },
            timelineStateController = ChatTimelineStateController(),
            mutationCoordinator = coordinator(),
            updateState = { transform -> state = transform(state) },
            projectMessageMutation = { projections += it },
        )
        blank.observeLocalMessages()

        localMessages.value = listOf(message("m1", 1L))
        advanceUntilIdle()

        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `authoritative DELETE projects Remove`() = runTest(dispatcher) {
        controller(backgroundScope).observeAuthoritativeMessageMutations()
        advanceUntilIdle()

        mutationEvents.emit(
            MessagingV2AuthoritativeMutation(
                conversationId = "chat",
                messageId = "m1",
                kind = MessageMutationKind.DELETE,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(MessageMutationProjection.Remove("m1")), projections)
    }

    @Test
    fun `authoritative REVOKE with message projects Set`() = runTest(dispatcher) {
        controller(backgroundScope).observeAuthoritativeMessageMutations()
        advanceUntilIdle()
        val revoked = message("m1", 1L)

        mutationEvents.emit(
            MessagingV2AuthoritativeMutation(
                conversationId = "chat",
                messageId = "m1",
                kind = MessageMutationKind.REVOKE,
                message = revoked,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(MessageMutationProjection.Set(revoked)), projections)
    }

    @Test
    fun `authoritative mutation for another conversation is ignored`() = runTest(dispatcher) {
        controller(backgroundScope).observeAuthoritativeMessageMutations()
        advanceUntilIdle()

        mutationEvents.emit(
            MessagingV2AuthoritativeMutation(
                conversationId = "other",
                messageId = "m1",
                kind = MessageMutationKind.DELETE,
            ),
        )
        advanceUntilIdle()

        assertTrue(projections.isEmpty())
    }

    @Test
    fun `authoritative EDIT without message projects nothing`() = runTest(dispatcher) {
        controller(backgroundScope).observeAuthoritativeMessageMutations()
        advanceUntilIdle()

        mutationEvents.emit(
            MessagingV2AuthoritativeMutation(
                conversationId = "chat",
                messageId = "m1",
                kind = MessageMutationKind.EDIT,
                message = null,
            ),
        )
        advanceUntilIdle()

        assertTrue(projections.isEmpty())
    }
}
