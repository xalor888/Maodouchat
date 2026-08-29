package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.messaging.v2.MessageMutationProjection

/** Owns deterministic timeline projections; callers retain persistence and sync responsibilities. */
internal class ChatTimelineStateController(
    private val merge: (List<Message>, List<Message>) -> List<Message> = ::mergeMessageVersions,
) {
    fun mergeIncoming(state: ChatDetailUiState, incoming: List<Message>): ChatDetailUiState =
        state.copy(messages = merge(state.messages, incoming))

    fun replaceMessage(
        state: ChatDetailUiState,
        messageId: String,
        transform: (Message) -> Message,
    ): ChatDetailUiState = state.copy(
        messages = state.messages.map { message ->
            if (message.id == messageId) transform(message) else message
        },
    )

    fun applyMutation(
        state: ChatDetailUiState,
        projection: MessageMutationProjection,
    ): ChatDetailUiState = when (projection) {
        is MessageMutationProjection.Remove -> state.copy(
            messages = state.messages.filterNot { it.id == projection.messageId },
        )
        is MessageMutationProjection.Set -> {
            val message = projection.message
            val messages = if (state.messages.any { it.id == message.id }) {
                state.messages.map { if (it.id == message.id) message else it }
            } else {
                merge(state.messages, listOf(message))
            }
            state.copy(messages = messages)
        }
    }

    fun initialHistoryLoaded(
        state: ChatDetailUiState,
        messages: List<Message>,
        unreadSeparatorId: String?,
    ): ChatDetailUiState = state.copy(
        messages = merge(state.messages, messages),
        unreadSeparatorId = unreadSeparatorId,
        hasMoreOlderMessages = false,
        initialTimelineReady = true,
        initialLoadError = null,
    )

    fun updateStatus(
        state: ChatDetailUiState,
        messageId: String,
        status: MessageStatus,
    ): ChatDetailUiState = replaceMessage(state, messageId) { message ->
        if (message.status.canAdvanceTo(status)) message.copy(status = status) else message
    }
}
