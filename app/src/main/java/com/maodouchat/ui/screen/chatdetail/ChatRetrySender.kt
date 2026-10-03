package com.maodouchat.ui.screen.chatdetail

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatRetrySender(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val sessionActive: (String) -> Boolean,
    private val outgoingFacade: ChatOutgoingFacade,
    private val persistMessage: suspend (Message) -> Unit,
    private val sendAttachmentRetry: (uri: Uri, type: MessageType, messageId: String, message: Message) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
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
        if (decision is ChatSendGuard.RetryDecision.NeedsAttachmentRetry) {
            return sendAttachmentRetry(
                decision.message.parsedContent().toUri(),
                decision.message.type,
                messageId,
                decision.message,
            )
        }
        if (decision is ChatSendGuard.RetryDecision.Reject) {
            if (decision.reason == ChatSendGuard.RetryRejectReason.NO_SESSION) {
                updateState { it.copy(groupEncryptionWarning = string(R.string.error_session_expired)) }
            }
            return
        }
        // G328c：原先这里是 `!!`——checkRetry 与这次查找之间，撤销/批量删除/服务端投影都可能把消息移走，用户点「重试」会崩。找不到即返回。
        val failedMsg = currentState().messages.find { it.id == messageId } ?: return
        val sendingMsg = failedMsg.copy(status = MessageStatus.SENDING)
        updateState { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) sendingMsg else m }) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
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
                    Log.w(
                        "ChatDetailViewModel",
                        "v2 retry enqueue failed: ${result.error.message}",
                        result.error,
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!sessionActive(retryOwnerUserId)) {
                    return@launch
                }
                updateState { st -> st.copy(messages = st.messages.map { m -> if (m.id == messageId) failedMsg else m }) }
                withContext(Dispatchers.IO) { persistMessage(failedMsg) }
                Log.w("ChatDetailViewModel", "v2 retry enqueue failed: ${error.message}", error)
            }
        }
    }

    private fun string(resourceId: Int, vararg args: Any): String = text(resourceId, args)
}
