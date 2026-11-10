package com.maodouchat.domain.messaging

import com.maodouchat.core.model.NudgeDecision
import com.maodouchat.core.model.NudgeRejectReason
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.OutgoingMessageCommand
import com.maodouchat.core.model.OutgoingMessageResult
import com.maodouchat.core.model.mergeMessageVersions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.ChatDetailUiState

class ChatNudgeSender(
    private val scope: CoroutineScope,
    private val nudgeEnabled: () -> Boolean,
    private val activeChatId: () -> String,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val mergeMessages: (List<Message>, List<Message>) -> List<Message>,
    private val emitListPreviewForDecrypted: (Message) -> Unit,
    private val outgoingFacade: ChatOutgoingFacade,
    private val text: (ChatSenderMessage, Array<out Any>) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun sendNudge() {
        // G68：nudge 守卫与待发意图构造都下沉到纯工厂（可单测），这里只编排副作用。
        val nudgeOwnerUserId = ownerUserId()
        val decision = ChatSendIntentFactory.checkNudge(
            state = currentState(),
            activeChatId = activeChatId(),
            ownerUserId = nudgeOwnerUserId,
            token = token(),
            nudgeEnabled = nudgeEnabled(),
        )
        if (decision is NudgeDecision.Reject) {
            // 资源字符串只出现在编排层：DISABLED 走 errorMessage，其余走 groupEncryptionWarning
            val disabled = decision.reason == NudgeRejectReason.DISABLED
            val message = when (decision.reason) {
                NudgeRejectReason.SECRET_CHAT -> string(ChatSenderMessage.SECRET_CHAT_FORWARD_BLOCKED)
                NudgeRejectReason.DISABLED -> string(ChatSenderMessage.NUDGE_DISABLED)
                NudgeRejectReason.NO_CHAT -> string(ChatSenderMessage.CHAT_WS_SEND_FAILED)
                NudgeRejectReason.NO_SESSION -> string(ChatSenderMessage.ERROR_SESSION_EXPIRED)
            }
            updateState {
                it.copy(
                    groupEncryptionWarning = message.takeUnless { disabled } ?: it.groupEncryptionWarning,
                    errorMessage = message.takeIf { disabled } ?: it.errorMessage,
                )
            }
            return
        }
        val contactName = currentState().contact.name.ifBlank { string(ChatSenderMessage.CHAT_OTHER_PERSON) }
        val optimistic = ChatSendIntentFactory.build(
            chatId = ChatSendIntentFactory.effectiveChatId(activeChatId(), chatId()),
            senderId = nudgeOwnerUserId,
            messageId = ChatSendIntentFactory.newMessageId(),
            timestamp = System.currentTimeMillis(),
            content = string(ChatSenderMessage.CHAT_NUDGE_YOU_NUDGED, contactName),
            type = MessageType.NUDGE,
            meta = null,
        )
        updateState { state ->
            state.copy(
                messages = mergeMessageVersions(state.messages, listOf(optimistic)),
                groupEncryptionWarning = null,
            )
        }
        scope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    outgoingFacade.enqueue(
                        OutgoingMessageCommand(
                            ownerUserId = nudgeOwnerUserId,
                            optimisticMessage = optimistic,
                            body = optimistic.content,
                            type = MessageType.NUDGE,
                        ),
                    )
                }
                when (result) {
                    is OutgoingMessageResult.Staged -> {
                        updateState { state ->
                            state.copy(messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            })
                        }
                        emitListPreviewForDecrypted(result.message)
                    }
                    is OutgoingMessageResult.Failed -> updateState { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            groupEncryptionWarning = result.error.message?.take(120)
                                ?: string(ChatSenderMessage.CHAT_SEND_FAILED),
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    private fun string(message: ChatSenderMessage, vararg args: Any): String = text(message, args)
}
