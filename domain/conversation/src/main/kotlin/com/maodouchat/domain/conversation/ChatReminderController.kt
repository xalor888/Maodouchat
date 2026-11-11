package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatReminderOutcome
import com.maodouchat.core.model.ChatScheduleController
import com.maodouchat.core.model.ChatScheduleRejection
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.MessageReminderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// 消息「稍后提醒」：排期结果映射与提醒列表直通 ChatScheduleController；
// 相对时间文案走 relativeTimeText 端口（app 侧用 DateUtils 实现）。
class ChatReminderController(
    private val chatScheduleController: ChatScheduleController,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val text: (ChatReminderMessage, Array<out Any>) -> String,
    private val relativeTimeText: (remindAtMillis: Long, nowMillis: Long) -> String,
    private val activeChatId: () -> String,
    private val chatId: String,
) {
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
                    groupEncryptionWarning = string(
                        ChatReminderMessage.REMINDER_SCHEDULED,
                        relativeTimeText(
                            result.reminder.remindAtMillis,
                            System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            is ChatReminderOutcome.Rejected -> uiState.update {
                it.copy(
                    groupEncryptionWarning = if (result.reason == ChatScheduleRejection.SESSION_MISSING) {
                        string(ChatReminderMessage.SESSION_EXPIRED)
                    } else {
                        string(ChatReminderMessage.SCHEDULE_FAILED)
                    },
                )
            }
        }
    }

    fun listRemindersForChat(chatId: String): List<MessageReminder> =
        chatScheduleController.listReminders(chatId)

    fun cancelReminder(reminderId: String) {
        chatScheduleController.cancelReminder(reminderId)
    }

    fun clearRemindersForChat(chatId: String) {
        chatScheduleController.clearReminders(chatId)
    }

    private fun string(message: ChatReminderMessage, vararg args: Any): String = text(message, args)
}
