package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupMutationAction
import com.maodouchat.core.model.GroupMutationFeedbackPolicy
import com.maodouchat.core.model.GroupSenderKeyMaintenanceOutcome
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sender Key 维护向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * senderKeyMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class SenderKeyMessage {
    SESSION_EXPIRED,
    KEY_REDISTRIBUTED,
    KEY_REDISTRIBUTE_FAILED,
}

/** 群 Sender Key 维护一族（手动重分发 / 自动重分发 / 失败呈现）。 */
class SenderKeyMaintenanceController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val text: (SenderKeyMessage) -> String,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val groupEncryptionHealthController: GroupEncryptionHealthController,
    private val onCommitted: (String?) -> Unit,
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun redistributeSenderKey() {
        if (chatId().isBlank() || token().isBlank()) {
            updateState {
                it.copy(
                    isUpdating = false,
                    message = text(SenderKeyMessage.SESSION_EXPIRED),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.SENDER_KEY,
                        IllegalStateException(text(SenderKeyMessage.SESSION_EXPIRED))
                    )
                )
            }
            return
        }
        val redisOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isUpdating = true, message = null, feedback = null) }
            try {
                if (!sessionActive(redisOwnerUserId)) {
                    updateState {
                        it.copy(
                            isUpdating = false,
                            message = text(SenderKeyMessage.SESSION_EXPIRED),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.SENDER_KEY,
                                IllegalStateException(text(SenderKeyMessage.SESSION_EXPIRED))
                            )
                        )
                    }
                    return@launch
                }
                val epoch = currentState().memberRevision
                val outcome = withContext(ioDispatcher) {
                    groupEncryptionHealthController.runManual(chatId(), epoch)
                }
                when (outcome) {
                    is GroupSenderKeyMaintenanceOutcome.Ready -> {
                        pendingRetrySet(null)
                        updateState {
                            it.copy(
                                isUpdating = false,
                                senderKeyStatus = outcome.status ?: it.senderKeyStatus,
                                localHasSenderKey = outcome.localHasSenderKey,
                            )
                        }
                        onCommitted(text(SenderKeyMessage.KEY_REDISTRIBUTED))
                    }
                    is GroupSenderKeyMaintenanceOutcome.Pending -> {
                        showSenderKeyMaintenanceFailure(
                            error = outcome.error,
                            status = outcome.status,
                            localHasSenderKey = outcome.localHasSenderKey,
                        )
                    }
                    is GroupSenderKeyMaintenanceOutcome.Failed ->
                        showSenderKeyMaintenanceFailure(outcome.error)
                    GroupSenderKeyMaintenanceOutcome.Skipped ->
                        updateState { it.copy(isUpdating = false) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                updateState { it.copy(isUpdating = false) }
                throw error
            }
        }
    }

    fun maybeAutoRedistributeSenderKey(state: GroupDetailUiState) {
        if (chatId().isBlank() || token().isBlank()) return
        val epoch = state.memberRevision
        if (epoch <= 0L) return
        scope.launch {
            try {
                val outcome = withContext(ioDispatcher) {
                    groupEncryptionHealthController.runAutomatic(
                        chatId = chatId(),
                        epoch = epoch,
                        currentStatus = state.senderKeyStatus,
                        localHasSenderKeyHint = state.localHasSenderKey,
                    )
                }
                when (outcome) {
                    is GroupSenderKeyMaintenanceOutcome.Ready -> updateState {
                        it.copy(
                            senderKeyStatus = outcome.status ?: it.senderKeyStatus,
                            localHasSenderKey = outcome.localHasSenderKey,
                        )
                    }
                    is GroupSenderKeyMaintenanceOutcome.Pending -> updateState {
                        it.copy(
                            senderKeyStatus = outcome.status ?: it.senderKeyStatus,
                            localHasSenderKey = outcome.localHasSenderKey,
                        )
                    }
                    is GroupSenderKeyMaintenanceOutcome.Failed,
                    GroupSenderKeyMaintenanceOutcome.Skipped -> Unit
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    private fun showSenderKeyMaintenanceFailure(
        error: Throwable,
        status: SenderKeyDistributionStatusDto? = null,
        localHasSenderKey: Boolean? = null,
    ) {
        val feedback = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.SENDER_KEY, error)
        pendingRetrySet { redistributeSenderKey() }
        updateState {
            it.copy(
                isUpdating = false,
                senderKeyStatus = status ?: it.senderKeyStatus,
                localHasSenderKey = localHasSenderKey ?: it.localHasSenderKey,
                message = feedback.detail ?: text(SenderKeyMessage.KEY_REDISTRIBUTE_FAILED),
                feedback = feedback.copy(canRetry = true),
            )
        }
    }
}
