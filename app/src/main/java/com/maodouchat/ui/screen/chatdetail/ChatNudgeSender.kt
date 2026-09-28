package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G345：`sendNudge()` 从 `ChatDetailViewModel` 抽出（纯搬移不改判断）——「拍一拍」的
 * 编排：守卫与待发意图构造走纯工厂 [ChatSendIntentFactory]（可单测），这里只做副作用
 * 编排（乐观上屏 → 出站队列 → 结果回填）。
 *
 * 依赖全经构造器注入（同 [ChatPinStarController] 一族）：会话身份/令牌/状态读写/
 * 消息合并/列表预览发射/出站门面/文案。组合期外不持有 VM 引用，不新增状态所有权。
 */
internal class ChatNudgeSender(
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
    private val text: (Int, Array<out Any>) -> String,
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
        if (decision is ChatSendIntentFactory.NudgeDecision.Reject) {
            // 资源字符串只出现在编排层：DISABLED 走 errorMessage，其余走 groupEncryptionWarning
            val disabled = decision.reason == ChatSendIntentFactory.NudgeRejectReason.DISABLED
            val message = when (decision.reason) {
                ChatSendIntentFactory.NudgeRejectReason.SECRET_CHAT -> string(R.string.secret_chat_forward_blocked)
                ChatSendIntentFactory.NudgeRejectReason.DISABLED -> string(R.string.nudge_disabled)
                ChatSendIntentFactory.NudgeRejectReason.NO_CHAT -> string(R.string.chat_ws_send_failed)
                ChatSendIntentFactory.NudgeRejectReason.NO_SESSION -> string(R.string.error_session_expired)
            }
            updateState {
                it.copy(
                    groupEncryptionWarning = message.takeUnless { disabled } ?: it.groupEncryptionWarning,
                    errorMessage = message.takeIf { disabled } ?: it.errorMessage,
                )
            }
            return
        }
        val contactName = currentState().contact.name.ifBlank { string(R.string.chat_other_person) }
        val optimistic = ChatSendIntentFactory.build(
            chatId = ChatSendIntentFactory.effectiveChatId(activeChatId(), chatId()),
            senderId = nudgeOwnerUserId,
            messageId = ChatSendIntentFactory.newMessageId(),
            timestamp = System.currentTimeMillis(),
            content = string(R.string.chat_nudge_you_nudged, contactName),
            type = MessageType.NUDGE,
            meta = null,
        )
        updateState { state ->
            state.copy(
                messages = mergeMessages(state.messages, listOf(optimistic)),
                groupEncryptionWarning = null,
            )
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
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
                                ?: string(R.string.chat_send_failed),
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    private fun string(resourceId: Int, vararg args: Any): String = text(resourceId, args)
}
