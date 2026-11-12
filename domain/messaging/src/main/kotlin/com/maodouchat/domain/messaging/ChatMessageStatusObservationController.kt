package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DisappearingMessagePolicy
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.SeenSet
import com.maodouchat.core.model.ChatReadWatermarkInput
import com.maodouchat.core.model.ChatReadWatermarkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.ChatDetailUiState

// 消息状态观察：时间线变化 → 标已读水印 + 持久化 + 已读回执入队。
// 从 ChatDetailViewModel 纯搬移；会话门禁/持久化/回执/Log 走端口，其余逻辑逐字平移。
class ChatMessageStatusObservationController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val chatId: String,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val getLastMessagesSeen: () -> List<Pair<String, MessageStatus>>?,
    private val setLastMessagesSeen: (List<Pair<String, MessageStatus>>?) -> Unit,
    private val readSeenMessages: SeenSet,
    private val setPendingReadWatermarkMessageId: (String?) -> Unit,
    private val getMarkReadJob: () -> Job?,
    private val setMarkReadJob: (Job?) -> Unit,
    private val armSecretDisappearing: suspend (String, String?) -> Unit,
    private val currentActiveChatId: () -> String?,
    private val markIncomingReadThrough: suspend (String, String, Long, String) -> Unit,
    private val markAllRead: suspend (String) -> Unit,
    private val sessionMayContinue: (String) -> Boolean,
    private val enqueueReadReceipt: suspend (String, String, Long?) -> Unit,
    private val emitChatRead: (String) -> Unit,
    private val logWarning: (String, Throwable?) -> Unit,
) {
    fun observeMessageStatus() {
        uiState
            .onEach { state ->
                val chat = state.chat ?: return@onEach
                val effectiveChatId = activeChatId().ifBlank { chatId }
                if (currentActiveChatId() != effectiveChatId) return@onEach
                val currentIds = state.messages.map { it.id to it.status }
                if (currentIds == getLastMessagesSeen()) return@onEach
                setLastMessagesSeen(currentIds)
                // G67：「算哪些新未读、水印选哪条」下沉到纯策略（可单测），这里只编排副作用。
                val liveOwnerUserId = ownerUserId()
                val plan = ChatReadWatermarkPolicy.plan(
                    input = ChatReadWatermarkInput(
                        messages = state.messages,
                        hasChat = true,
                        isActiveChat = true,
                        ownerUserId = liveOwnerUserId,
                        sessionMayContinue = sessionMayContinue(liveOwnerUserId),
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
                    // 函数类型端口不支持具名实参，这里一律用位置实参（顺序与 ChatDetailDataAccess 签名一致）。
                    markIncomingReadThrough(
                        effectiveChatId,
                        liveOwnerUserId,
                        watermarkTimestamp,
                        watermarkId,
                    )
                    markAllRead(effectiveChatId)
                }
                getMarkReadJob()?.cancel()
                setMarkReadJob(scope.launch {
                    if (DisappearingMessagePolicy.shouldSkipReadReceipts(state.isSecretChat == true)) {
                        armSecretDisappearing(effectiveChatId, watermarkId)
                        return@launch
                    }
                    delay(500)
                    if (!sessionMayContinue(liveOwnerUserId)
                    ) {
                        return@launch
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            enqueueReadReceipt(
                                effectiveChatId,
                                watermarkId,
                                chat.memberRevision.takeIf { chat.isGroup },
                            )
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // G67：入队失败必须回滚 seen + 短路标记，否则这条消息永远不会重试
                        ChatReadWatermarkPolicy.rollbackAfterFailure(readSeenMessages, plan)
                        setLastMessagesSeen(null)
                        logWarning("v2 read receipt enqueue failed: " + error.message, error)
                    }
                })
                emitChatReadForCurrentChat()
            }
            .launchIn(scope)
    }

    private fun emitChatReadForCurrentChat() {
        val id = activeChatId().ifBlank { chatId }
        if (id.isBlank()) return
        emitChatRead(id)
    }
}
