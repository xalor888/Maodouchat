package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 内联内容发送（贴纸 / 实时位置 / 截屏告警文本）：乐观上屏 + V2 出站编排。
// 从 ChatDetailViewModel 纯搬移；调用方（扩展函数、截屏告警控制器）走 VM 的同签名委托。
internal class ChatInlineSendController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val mergeMessages: (List<Message>, List<Message>) -> List<Message>,
    private val completions: ConcurrentHashMap<String, CompletableDeferred<Boolean>>,
    private val outgoingFacade: ChatOutgoingFacade,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)

    internal fun sendInlineContent(content: String, type: MessageType, preview: String): String? {
        val sendOwnerUserId = ownerUserId()
        if (token().isBlank() || sendOwnerUserId.isBlank()) {
            updateState {
                it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
            }
            return null
        }
        val msgId = "m_${UUID.randomUUID()}"
        val optimistic = Message(
            id = msgId,
            chatId = activeChatId(),
            senderId = sendOwnerUserId,
            content = content,
            type = type,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
        )
        val completion = CompletableDeferred<Boolean>()
        completions[msgId] = completion
        updateState { it.copy(messages = mergeMessages(it.messages, listOf(optimistic)), isSending = true) }
        scope.launch {
            enqueueInlineViaMessagingV2(
                optimistic = optimistic,
                content = content,
                type = type,
                preview = preview,
                completion = completion,
            )
        }
        return msgId
    }

    private suspend fun enqueueInlineViaMessagingV2(
        optimistic: Message,
        content: String,
        type: MessageType,
        preview: String,
        completion: CompletableDeferred<Boolean>,
    ) {
        try {
            val result = withContext(Dispatchers.IO) {
                outgoingFacade.enqueue(
                    OutgoingMessageCommand(
                        ownerUserId = optimistic.senderId,
                        optimisticMessage = optimistic,
                        body = content,
                        type = type,
                    ),
                )
            }
            when (result) {
                is OutgoingMessageResult.Staged -> {
                    updateState { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            isSending = false,
                        )
                    }
                    ChatDetailAccess.emitMessageSent(result.message.chatId, preview, type.name)
                    completion.complete(true)
                }
                is OutgoingMessageResult.Failed -> {
                    updateState { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            isSending = false,
                            groupEncryptionWarning = result.error.message?.take(120)
                                ?: text(R.string.chat_send_failed),
                        )
                    }
                    completion.complete(false)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            updateState { it.copy(isSending = false) }
            completion.cancel(error)
            throw error
        } finally {
            completions.remove(optimistic.id, completion)
        }
    }
}
