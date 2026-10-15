package com.maodouchat.scheduling

import com.maodouchat.util.MessageReminder
import com.maodouchat.util.ScheduledMessage

data class ScheduledMessageRequest(
    val chatId: String,
    val peerUserId: String,
    val text: String,
    val sendAtMillis: Long,
    val isGroup: Boolean,
    val repeatIntervalMs: Long = 0L,
    val repeatCount: Int = 0,
    val weekdaysOnly: Boolean = false,
)

data class MessageReminderRequest(
    val chatId: String,
    val messageId: String,
    val messagePreview: String,
    val remindAtMillis: Long,
)

enum class ConversationScheduleFailure {
    SESSION_MISSING,
    INVALID_REQUEST,
    LIMIT_REACHED,
    NOT_FOUND,
    STORAGE,
}

sealed interface ConversationScheduleResult<out T> {
    data class Success<T>(val value: T) : ConversationScheduleResult<T>
    data class Failure(
        val reason: ConversationScheduleFailure,
        val cause: Throwable? = null,
    ) : ConversationScheduleResult<Nothing>
}

interface ConversationScheduleBackend {
    fun listAllScheduled(ownerUserId: String): List<ScheduledMessage>
    fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage>
    fun getScheduled(ownerUserId: String, id: String): ScheduledMessage?
    fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage?
    fun updateScheduled(
        ownerUserId: String,
        id: String,
        text: String?,
        sendAtMillis: Long?,
    ): ScheduledMessage?
    fun removeScheduled(ownerUserId: String, id: String): Boolean
    fun clearScheduled(ownerUserId: String, chatId: String): List<String>
    fun scheduleJob(item: ScheduledMessage)
    fun cancelJob(id: String)
    fun rescheduleJob(item: ScheduledMessage)

    fun listReminders(ownerUserId: String): List<MessageReminder>
    fun upsertReminder(reminder: MessageReminder)
    fun removeReminder(ownerUserId: String, id: String)
    fun clearReminders(ownerUserId: String, chatId: String)
    fun scheduleReminderJob(reminder: MessageReminder)
    fun cancelReminderJob(id: String)
}
