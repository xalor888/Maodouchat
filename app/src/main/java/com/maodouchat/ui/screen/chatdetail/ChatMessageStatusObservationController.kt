package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.chatdetail.ChatDetailDataAccess
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.DisappearingMessagePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 消息状态观察：时间线变化 → 标已读水印 + 持久化 + 已读回执入队。
// 从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatMessageStatusObservationController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val chatId: String,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val getLastMessagesSeen: () -> List<Pair<String, MessageStatus>>?,
    private val setLastMessagesSeen: (List<Pair<String, MessageStatus>>?) -> Unit,
    private val readSeenMessages: ChatReadWatermarkPolicy.SeenSet,
    private val setPendingReadWatermarkMessageId: (String?) -> Unit,
    private val getMarkReadJob: () -> Job?,
    private val setMarkReadJob: (Job?) -> Unit,
    private val armSecretDisappearing: suspend (String, String?) -> Unit,
) {
    internal fun observeMessageStatus() {
        uiState
            .onEach { state ->
                if (state.chat == null) return@onEach
                val effectiveChatId = activeChatId().ifBlank { chatId }
                if (ChatDetailAccess.activeChatId() != effectiveChatId) return@onEach
                val currentIds = state.messages.map { it.id to it.status }
                if (currentIds == getLastMessagesSeen()) return@onEach
                setLastMessagesSeen(currentIds)
                // G67：「算哪些新未读、水印选哪条」下沉到纯策略（可单测），这里只编排副作用。
                val liveOwnerUserId = ownerUserId()
                val plan = ChatReadWatermarkPolicy.plan(
                    input = ChatReadWatermarkPolicy.Input(
                        messages = state.messages,
                        hasChat = true,
                        isActiveChat = true,
                        ownerUserId = liveOwnerUserId,
                        sessionMayContinue = BackgroundSessionGate.mayContinue(
                            expectedUserId = liveOwnerUserId,
                        ),
                    ),
                    seen = readSeenMessages,
                ) ?: return@onEach
                val unreadIds = plan.unreadIds
                val watermarkId = plan.watermarkMessageId
                val watermarkTimestamp = plan.watermarkTimestamp
                setPendingReadWatermarkMessageId(watermarkId)
                updateState { current ->
                    current.copy(
                        messages = current.messages.map { message ->
                            if (message.id in unreadIds) message.copy(status = MessageStatus.READ) else message
                        },
                    )
                }
                scope.launch(Dispatchers.IO) {
                    ChatDetailDataAccess.markIncomingReadThrough(
                        chatId = effectiveChatId,
                        ownerUserId = liveOwnerUserId,
                        throughTimestamp = watermarkTimestamp,
                        throughMessageId = watermarkId,
                    )
                    ChatDetailDataAccess.markAllRead(effectiveChatId)
                }
                getMarkReadJob()?.cancel()
                setMarkReadJob(scope.launch {
                    if (DisappearingMessagePolicy.shouldSkipReadReceipts(state.isSecretChat == true)) {
                        armSecretDisappearing(effectiveChatId, watermarkId)
                        return@launch
                    }
                    delay(500)
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = liveOwnerUserId,
                    )
                    ) {
                        return@launch
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            ChatDetailAccess.messagingOutbox.enqueueReadReceipt(
                                conversationId = effectiveChatId,
                                throughMessageId = watermarkId,
                                groupRevision = state.chat.memberRevision.takeIf { state.chat.isGroup },
                            )
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // G67：入队失败必须回滚 seen + 短路标记，否则这条消息永远不会重试
                        ChatReadWatermarkPolicy.rollbackAfterFailure(readSeenMessages, plan)
                        setLastMessagesSeen(null)
                        Log.w("ChatDetailViewModel", "v2 read receipt enqueue failed: " + error.message, error)
                    }
                })
                emitChatReadForCurrentChat()
            }
            .launchIn(scope)
    }

    private fun emitChatReadForCurrentChat() {
        val id = activeChatId().ifBlank { chatId }
        if (id.isBlank()) return
        ChatDetailAccess.emitChatRead(id)
    }
}
