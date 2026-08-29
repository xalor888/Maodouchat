package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.scheduling.ChatReminderOutcome
import com.maodouchat.scheduling.ChatScheduleController
import com.maodouchat.scheduling.ChatScheduleRejection
import com.maodouchat.scheduling.MessageReminderRequest
import com.maodouchat.util.MessageReminderStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 消息「稍后提醒」控制器。从 ChatDetailViewModel 抽出，只依赖注入的
 * ChatScheduleController / uiState / text，不依赖 ViewModel 或 Application 单例。
 */
class ChatReminderController(
    private val chatScheduleController: ChatScheduleController,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val activeChatId: () -> String,
    private val chatId: String,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)
    /** 消息「稍后提醒」——本地 WorkManager 到点发通知，点击直达本聊天并高亮原消息。 */
    fun scheduleMessageReminder(message: Message, remindAtMillis: Long) {
        val chat = activeChatId().ifBlank { chatId }
        if (chat.isBlank()) return
        when (val result = chatScheduleController.scheduleReminder(
            MessageReminderRequest(
                chatId = chat,
                messageId = message.id,
                messagePreview = message.parsedContent(),
                remindAtMillis = remindAtMillis,
            )
        )) {
            is ChatReminderOutcome.Scheduled -> uiState.update {
                it.copy(
                    groupEncryptionWarning = text(
                        R.string.message_reminder_scheduled,
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            result.reminder.remindAtMillis,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS,
                        ),
                    ),
                )
            }
            is ChatReminderOutcome.Rejected -> uiState.update {
                it.copy(
                    groupEncryptionWarning = if (result.reason == ChatScheduleRejection.SESSION_MISSING) {
                        text(R.string.error_session_expired)
                    } else {
                        text(R.string.schedule_failed)
                    },
                )
            }
        }
    }

    fun listRemindersForChat(chatId: String): List<MessageReminderStore.MessageReminder> =
        chatScheduleController.listReminders(chatId)

    fun cancelReminder(reminderId: String) {
        chatScheduleController.cancelReminder(reminderId)
    }

    fun clearRemindersForChat(chatId: String) {
        chatScheduleController.clearReminders(chatId)
    }
}
