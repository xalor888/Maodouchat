package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatRetryDecision
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.RetryRejectReason
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.OutgoingMessageCommand
import com.maodouchat.core.model.OutgoingMessageResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.ChatDetailUiState

class ChatRetrySender(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val sessionActive: (String) -> Boolean,
    private val outgoingFacade: ChatOutgoingFacade,
    private val persistMessage: suspend (Message) -> Unit,
    private val sendAttachmentRetry: (uriString: String, type: MessageType, messageId: String, message: Message) -> Unit,
    private val text: (ChatSenderMessage, Array<out Any>) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logWarning: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /**
     * 重发失败的消息
     * - 将消息状态改回 SENDING，然后重新走发送流程
     * - TEXT: 直接重新加密发送
     * - FILE/IMAGE/GIF/VIDEO/VOICE: 恢复持久化对象传输，或从保留的本机源重新加密
     */
    fun retrySendMessage(messageId: String) {
        // G66：重试准入同样走纯函数（归属、状态、类型三条一起判）
        val retryOwnerUserId = ownerUserId()
        val decision = ChatSendGuard.checkRetry(currentState(), messageId, retryOwnerUserId, token())
        if (decision is ChatRetryDecision.NeedsAttachmentRetry) {
            return sendAttachmentRetry(
                decision.message.parsedContent(),
                decision.message.type,
                messageId,
                decision.message,
            )
        }
        if (decision is ChatRetryDecision.Reject) {
            if (decision.reason == RetryRejectReason.NO_SESSION) {
                updateState { it.copy(groupEncryptionWarning = string(ChatSenderMessage.ERROR_SESSION_EXPIRED)) }
            }
            return
        }
        // G328c：原先这里是 `!!`——checkRetry 与这次查找之间，撤销/批量删除/服务端投影都可能把消息移走，用户点「重试」会崩。找不到即返回。
        val failedMsg = currentState().messages.find { it.id == messageId } ?: return
        val sendingMsg = failedMsg.copy(status = MessageStatus.SENDING)
        updateState { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) sendingMsg else m }) }
        scope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    outgoingFacade.retry(
                        OutgoingMessageCommand(
                            ownerUserId = retryOwnerUserId,
                            optimisticMessage = sendingMsg,
                            body = sendingMsg.content,
                            type = sendingMsg.type,
                        ),
                    )
                }
                updateState { state ->
                    state.copy(messages = state.messages.map { message ->
                        when {
                            message.id != messageId -> message
                            result is OutgoingMessageResult.Staged -> result.message
                            result is OutgoingMessageResult.Failed -> result.message
                            else -> message
                        }
                    })
                }
                if (result is OutgoingMessageResult.Failed) {
                    logWarning("v2 retry enqueue failed: ${result.error.message}", result.error)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!sessionActive(retryOwnerUserId)) {
                    return@launch
                }
                updateState { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) failedMsg else m }) }
                withContext(ioDispatcher) { persistMessage(failedMsg) }
                logWarning("v2 retry enqueue failed: ${error.message}", error)
            }
        }
    }

    private fun string(message: ChatSenderMessage, vararg args: Any): String = text(message, args)
}
