package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.attachment.AttachmentTransferScheduler
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.chatdetail.ChatDetailDataAccess
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.Message
import com.maodouchat.session.CurrentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// 附件观察：附件落库事件回写时间线 + 传输状态观察与最终发送调度。
// 从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatAttachmentObservationController(
    private val scope: CoroutineScope,
    private val getApplication: () -> Application,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val activeChatId: () -> String,
    private val mergeMessages: (List<Message>, List<Message>) -> List<Message>,
    private val mediaStateController: ChatMediaStateController,
) {
    internal fun observeAttachmentFinalizedEvents() {
        scope.launch {
            ChatDetailAccess.attachmentFinalizedEvents.collect { event ->
                if (event.sessionGeneration != ChatDetailAccess.currentSessionGeneration()) {
                    return@collect
                }
                val message = event.message
                if (message.chatId != activeChatId()) return@collect
                updateState { state ->
                    state.copy(
                        messages = mergeMessages(state.messages.filterNot { it.id == message.id }, listOf(message)),
                        fileTransferProgress = state.fileTransferProgress - message.id,
                        fileTransferStates = state.fileTransferStates - message.id,
                        fileTransferErrors = state.fileTransferErrors - message.id,
                        preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - message.id
                    )
                }
            }
        }
    }

    internal fun observeAttachmentTransfers() {
        ChatDetailDataAccess.observeAllAttachmentTransfers()
            .onEach { allTransfers ->
                val liveOwnerUserId = CurrentSession.ownerUserId()
                if (liveOwnerUserId.isBlank()) return@onEach
                val visibleMessageIds = currentState().messages.mapTo(hashSetOf()) { it.id }
                // Only this account's rows — SQLCipher wipe is primary isolation, this is defense-in-depth mid-switch.
                val transfers = allTransfers.filter { transfer ->
                    transfer.ownerUserId == liveOwnerUserId &&
                        (transfer.chatId == activeChatId() || transfer.messageId in visibleMessageIds)
                }
                updateState { state -> mediaStateController.applyTransfers(state, transfers) }
                transfers.filter { it.chatId == activeChatId() && it.state == AttachmentTransferState.READY }.forEach { transfer ->
                    // Final send is owned by WorkManager so it survives navigation/process death.
                    AttachmentTransferScheduler.schedule(
                        getApplication(),
                        transfer.messageId,
                        transfer.ownerUserId
                    )
                }
            }
            .launchIn(scope)
    }
}
