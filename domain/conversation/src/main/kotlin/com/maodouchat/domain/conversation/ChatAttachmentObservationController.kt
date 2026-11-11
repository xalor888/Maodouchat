package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AttachmentFinalizedEvent
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.mergeMessageVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// 附件观察：附件落库事件回写时间线 + 传输状态观察与最终发送调度。
// 落库事件流/会话世代/传输流/属主/最终发送走端口；其余逻辑逐字平移。
class ChatAttachmentObservationController(
    private val scope: CoroutineScope,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val activeChatId: () -> String,
    private val mergeMessages: (List<Message>, List<Message>) -> List<Message>,
    private val mediaStateController: ChatMediaStateController,
    private val attachmentFinalizedEvents: Flow<AttachmentFinalizedEvent>,
    private val currentSessionGeneration: () -> Long,
    private val attachmentTransfers: Flow<List<AttachmentTransferObservation>>,
    private val ownerUserId: () -> String,
    private val scheduleFinalSend: (String, String) -> Unit,
) {
    fun observeAttachmentFinalizedEvents() {
        scope.launch {
            attachmentFinalizedEvents.collect { event ->
                if (event.sessionGeneration != currentSessionGeneration()) {
                    return@collect
                }
                val message = event.message
                if (message.chatId != activeChatId()) return@collect
                updateState { state ->
                    state.copy(
                        messages = mergeMessageVersions(state.messages.filterNot { it.id == message.id }, listOf(message)),
                        fileTransferProgress = state.fileTransferProgress - message.id,
                        fileTransferStates = state.fileTransferStates - message.id,
                        fileTransferErrors = state.fileTransferErrors - message.id,
                        preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - message.id
                    )
                }
            }
        }
    }

    fun observeAttachmentTransfers() {
        attachmentTransfers
            .onEach { allTransfers ->
                val liveOwnerUserId = ownerUserId()
                if (liveOwnerUserId.isBlank()) return@onEach
                val visibleMessageIds = currentState().messages.mapTo(hashSetOf()) { it.id }
                // Only this account's rows — SQLCipher wipe is primary isolation, this is defense-in-depth mid-switch.
                val transfers = allTransfers.filter { transfer ->
                    transfer.ownerUserId == liveOwnerUserId &&
                        (transfer.chatId == activeChatId() || transfer.messageId in visibleMessageIds)
                }
                val snapshots = transfers.map {
                    AttachmentTransferSnapshot(
                        messageId = it.messageId,
                        state = it.state,
                        uploadedBytes = it.uploadedBytes,
                        cipherSize = it.cipherSize,
                        lastErrorCode = it.lastErrorCode,
                    )
                }
                updateState { state -> mediaStateController.applyTransfers(state, snapshots) }
                transfers.filter { it.chatId == activeChatId() && it.state == AttachmentTransferState.READY }.forEach { transfer ->
                    // Final send is owned by WorkManager so it survives navigation/process death.
                    scheduleFinalSend(transfer.messageId, transfer.ownerUserId)
                }
            }
            .launchIn(scope)
    }
}
