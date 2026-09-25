package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.network.TokenManager
import com.maodouchat.scheduling.ChatScheduleCommand
import com.maodouchat.scheduling.ChatScheduleController
import com.maodouchat.scheduling.ChatScheduleImmediateOutcome
import com.maodouchat.scheduling.ChatScheduleMutationOutcome
import com.maodouchat.scheduling.ChatScheduleRejection
import com.maodouchat.util.ScheduledMessagePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 定时发送控制器。从 ChatDetailViewModel 抽出；「立即发送」通过 [sendMessage] 端口回调
 * 复用普通消息发送路径（不另建加密链路），只依赖注入的 schedule 控制器 / uiState / text。
 */
class ScheduledMessageController(
    private val chatScheduleController: ChatScheduleController,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val tokenManager: TokenManager,
    private val activeChatId: () -> String,
    private val chatId: String,
    private val clearDraft: () -> Unit,
    private val sendMessage: (forceText: String?, onDurableCommit: (() -> Unit)?, onDurableFailure: (() -> Unit)?) -> Boolean,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)

    fun refreshScheduledMessages() {
        val chat = activeChatId().ifBlank { chatId }
        uiState.update { it.copy(scheduledMessages = chatScheduleController.listScheduled(chat)) }
    }

    fun clearScheduledInfo() {
        uiState.update { it.copy(scheduledInfoMessage = null) }
    }

    /** 将当前输入排队为定时发送（1:1 与群聊纯文本）。成功后清空输入框。 */
    fun scheduleMessage(delayMs: Long) {
        scheduleMessageAt(System.currentTimeMillis() + delayMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS))
    }

    /** 按绝对时间排队定时发送（会 clamp 到策略允许窗口）。 */
    fun scheduleMessageAt(sendAtMillis: Long, repeatIntervalMs: Long = 0L, repeatCount: Int = 0, weekdaysOnly: Boolean = false) {
        val state = uiState.value
        val targetChatId = activeChatId().ifBlank { chatId }
        when (val result = chatScheduleController.queue(
            ChatScheduleCommand(
                chatId = targetChatId,
                peerUserId = state.contact.id,
                text = state.inputText.trim(),
                isGroup = state.chat?.isGroup == true,
                sessionAvailable = com.maodouchat.session.CurrentSession.hasSession(),
                blocked = state.isContactBlocked,
                sendAtMillis = sendAtMillis,
                repeatIntervalMs = repeatIntervalMs,
                repeatCount = repeatCount,
                weekdaysOnly = weekdaysOnly,
            ),
        )) {
            is ChatScheduleMutationOutcome.Applied -> {
                clearDraft()
                uiState.update {
                    it.copy(
                        inputText = "",
                        scheduledMessages = result.scheduledMessages,
                        scheduledInfoMessage = text(
                            R.string.schedule_queued_at,
                            formatScheduleTimeHint(requireNotNull(result.effectiveAtMillis)),
                        ),
                    )
                }
            }
            is ChatScheduleMutationOutcome.Rejected -> projectScheduleQueueRejection(result.reason)
        }
    }

    /** 重复定时发送（首次在间隔后，此后每间隔自动重发）。可限制重复次数（0=不限）、可仅工作日。 */
    fun scheduleMessageRepeat(intervalMs: Long, repeatCount: Int = 0, weekdaysOnly: Boolean = false) {
        if (intervalMs <= 0) return
        scheduleMessageAt(
            System.currentTimeMillis() + intervalMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS),
            repeatIntervalMs = intervalMs,
            repeatCount = repeatCount,
            weekdaysOnly = weekdaysOnly
        )
    }

    fun cancelScheduledMessage(id: String) {
        val targetChatId = activeChatId().ifBlank { chatId }
        when (val result = chatScheduleController.cancel(targetChatId, id)) {
            is ChatScheduleMutationOutcome.Applied -> {
                uiState.update {
                    it.copy(
                        scheduledMessages = result.scheduledMessages,
                        scheduledInfoMessage = text(R.string.schedule_cancelled),
                    )
                }
            }
            is ChatScheduleMutationOutcome.Rejected -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.schedule_failed))
            }
        }
    }

    /** 定时消息立即发送（取消定时，按普通消息全加密路径发送，不扰动输入框草稿）。 */
    fun sendScheduledNow(id: String) {
        when (val result = chatScheduleController.beginImmediateSend(id)) {
            is ChatScheduleImmediateOutcome.Ready -> {
                val scheduleOwnerUserId = result.item.ownerUserId
                val accepted = sendMessage(
                    result.item.text,
                    {
                        chatScheduleController.completeImmediateSend(scheduleOwnerUserId, id)
                        if (tokenManager.getUserId() == scheduleOwnerUserId) {
                            refreshScheduledMessages()
                            uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_sent_now)) }
                        }
                    },
                    {
                        chatScheduleController.restoreImmediateSend(scheduleOwnerUserId, id)
                        if (tokenManager.getUserId() == scheduleOwnerUserId) {
                            refreshScheduledMessages()
                        }
                    },
                )
                if (!accepted) {
                    chatScheduleController.restoreImmediateSend(scheduleOwnerUserId, id)
                    refreshScheduledMessages()
                    uiState.update { it.copy(scheduledInfoMessage = text(R.string.schedule_failed)) }
                }
            }
            is ChatScheduleImmediateOutcome.Rejected -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.schedule_failed))
            }
        }
    }

    /** 取消本会话全部定时消息。 */
    fun cancelAllScheduledMessages() {
        val targetChatId = activeChatId().ifBlank { chatId }
        if (targetChatId.isBlank()) return
        when (val result = chatScheduleController.cancelAll(targetChatId)) {
            is ChatScheduleMutationOutcome.Applied -> {
                uiState.update {
                    it.copy(
                        scheduledMessages = result.scheduledMessages,
                        scheduledInfoMessage = text(R.string.schedule_cancelled_all),
                    )
                }
            }
            is ChatScheduleMutationOutcome.Rejected -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.schedule_failed))
            }
        }
    }

    /** 改期已排队的定时消息（可选改文案）。 */
    fun rescheduleScheduledMessage(id: String, delayMs: Long, newText: String? = null) {
        rescheduleScheduledMessageAt(
            id = id,
            sendAtMillis = System.currentTimeMillis() + delayMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS),
            newText = newText
        )
    }

    fun rescheduleScheduledMessageAt(id: String, sendAtMillis: Long, newText: String? = null) {
        val targetChatId = activeChatId().ifBlank { chatId }
        when (val result = chatScheduleController.reschedule(targetChatId, id, sendAtMillis, newText)) {
            is ChatScheduleMutationOutcome.Applied -> {
                uiState.update {
                    it.copy(
                        scheduledMessages = result.scheduledMessages,
                        scheduledInfoMessage = text(
                            R.string.schedule_rescheduled,
                            formatScheduleTimeHint(requireNotNull(result.effectiveAtMillis)),
                        ),
                    )
                }
            }
            is ChatScheduleMutationOutcome.Rejected -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.schedule_failed))
            }
        }
    }

    private fun projectScheduleQueueRejection(reason: ChatScheduleRejection) {
        when (reason) {
            ChatScheduleRejection.INVALID_REQUEST -> Unit
            ChatScheduleRejection.DISABLED -> uiState.update {
                it.copy(groupEncryptionWarning = text(R.string.scheduled_messages_disabled))
            }
            ChatScheduleRejection.SESSION_MISSING -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.error_session_expired))
            }
            ChatScheduleRejection.BLOCKED -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.chat_blocked_user_status, it.contact.displayName))
            }
            ChatScheduleRejection.LIMIT_REACHED -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.schedule_limit_reached))
            }
            ChatScheduleRejection.NOT_FOUND,
            ChatScheduleRejection.STORAGE -> uiState.update {
                it.copy(scheduledInfoMessage = text(R.string.schedule_failed))
            }
        }
    }

    private fun formatScheduleTimeHint(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }
}
