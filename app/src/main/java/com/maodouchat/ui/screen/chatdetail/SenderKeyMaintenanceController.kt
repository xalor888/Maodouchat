package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.group.GroupEncryptionHealthController
import com.maodouchat.group.GroupMutationAction
import com.maodouchat.group.GroupMutationFeedbackPolicy
import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceOutcome
import com.maodouchat.network.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G366：群 sender key 维护一族（手动重分发 / 自动重分发 / 失败呈现）从
 * `GroupDetailViewModel` 抽出（纯搬移不改判断）——`groupEncryptionHealthController`
 * 仍由 VM 持有（load 也用它取状态），经构造器注入共享；pendingRetry 经 set lambda 共享。
 */
internal class SenderKeyMaintenanceController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val groupEncryptionHealthController: GroupEncryptionHealthController,
    private val onCommitted: (String?) -> Unit,
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
) {
    fun redistributeSenderKey() {
        if (chatId().isBlank() || token().isBlank()) {
            updateState {
                it.copy(
                    isUpdating = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.SENDER_KEY,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val redisOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isUpdating = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = redisOwnerUserId,
                )
                ) {
                    updateState {
                        it.copy(
                            isUpdating = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.SENDER_KEY,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val epoch = currentState().memberRevision
                val outcome = withContext(Dispatchers.IO) {
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
                        onCommitted(text(R.string.group_detail_key_redistributed))
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

    internal fun maybeAutoRedistributeSenderKey(state: GroupDetailUiState) {
        if (chatId().isBlank() || token().isBlank()) return
        val epoch = state.memberRevision
        if (epoch <= 0L) return
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
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
                message = feedback.detail ?: text(R.string.group_detail_key_redistribute_failed),
                feedback = feedback.copy(canRetry = true),
            )
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
