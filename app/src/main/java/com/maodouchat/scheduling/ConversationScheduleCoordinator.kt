package com.maodouchat.scheduling

import android.content.Context
import com.maodouchat.util.MessageReminderPolicy
import com.maodouchat.util.MessageReminderScheduler
import com.maodouchat.util.MessageReminderStore
import com.maodouchat.util.ScheduledMessage
import com.maodouchat.util.ScheduledMessagePolicy
import com.maodouchat.util.ScheduledMessageScheduler
import com.maodouchat.util.ScheduledMessageStore
import java.util.UUID

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

    fun listReminders(ownerUserId: String): List<MessageReminderStore.MessageReminder>
    fun upsertReminder(reminder: MessageReminderStore.MessageReminder)
    fun removeReminder(ownerUserId: String, id: String)
    fun clearReminders(ownerUserId: String, chatId: String)
    fun scheduleReminderJob(reminder: MessageReminderStore.MessageReminder)
    fun cancelReminderJob(id: String)
}

class AndroidConversationScheduleBackend(context: Context) : ConversationScheduleBackend {
    private val appContext = context.applicationContext

    override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> =
        ScheduledMessageStore.listForUser(appContext, ownerUserId)

    override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> =
        ScheduledMessageStore.listForChatForUser(appContext, chatId, ownerUserId)

    override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? =
        ScheduledMessageStore.getForUser(appContext, id, ownerUserId)

    override fun addScheduled(
        ownerUserId: String,
        request: ScheduledMessageRequest,
    ): ScheduledMessage? = ScheduledMessageStore.addForUser(
        context = appContext,
        ownerUserId = ownerUserId,
        chatId = request.chatId,
        peerUserId = request.peerUserId,
        text = request.text,
        sendAtMillis = request.sendAtMillis,
        isGroup = request.isGroup,
        repeatIntervalMs = request.repeatIntervalMs,
        repeatCount = request.repeatCount,
        weekdaysOnly = request.weekdaysOnly,
    )

    override fun updateScheduled(
        ownerUserId: String,
        id: String,
        text: String?,
        sendAtMillis: Long?,
    ): ScheduledMessage? = ScheduledMessageStore.updateTextAndTimeForUser(
        context = appContext,
        ownerUserId = ownerUserId,
        id = id,
        text = text,
        sendAtMillis = sendAtMillis,
    )

    override fun removeScheduled(ownerUserId: String, id: String): Boolean =
        ScheduledMessageStore.removeForUser(appContext, id, ownerUserId)

    override fun clearScheduled(ownerUserId: String, chatId: String): List<String> =
        ScheduledMessageStore.clearForChatForUser(appContext, chatId, ownerUserId)

    override fun scheduleJob(item: ScheduledMessage) =
        ScheduledMessageScheduler.schedule(appContext, item)

    override fun cancelJob(id: String) = ScheduledMessageScheduler.cancel(appContext, id)

    override fun rescheduleJob(item: ScheduledMessage) =
        ScheduledMessageScheduler.reschedule(appContext, item)

    override fun listReminders(ownerUserId: String): List<MessageReminderStore.MessageReminder> =
        MessageReminderStore.list(appContext, ownerUserId)

    override fun upsertReminder(reminder: MessageReminderStore.MessageReminder) =
        MessageReminderStore.upsert(appContext, reminder)

    override fun removeReminder(ownerUserId: String, id: String) =
        MessageReminderStore.remove(appContext, id, ownerUserId)

    override fun clearReminders(ownerUserId: String, chatId: String) =
        MessageReminderStore.clearForChat(appContext, chatId, ownerUserId)

    override fun scheduleReminderJob(reminder: MessageReminderStore.MessageReminder) =
        MessageReminderScheduler.schedule(appContext, reminder)

    override fun cancelReminderJob(id: String) = MessageReminderScheduler.cancel(appContext, id)
}

class ConversationScheduleCoordinator(
    private val ownerUserId: () -> String,
    private val backend: ConversationScheduleBackend,
    private val now: () -> Long = System::currentTimeMillis,
    private val reminderId: () -> String = { "mr_${UUID.randomUUID()}" },
) {
    fun listAllScheduled(): List<ScheduledMessage> {
        val owner = ownerUserId().trim()
        if (owner.isBlank()) return emptyList()
        return backend.listAllScheduled(owner)
    }

    fun listScheduled(chatId: String): List<ScheduledMessage> {
        val owner = ownerUserId().trim()
        if (owner.isBlank() || chatId.isBlank()) return emptyList()
        return backend.listScheduled(owner, chatId)
    }

    fun queue(request: ScheduledMessageRequest): ConversationScheduleResult<ScheduledMessage> {
        val owner = ownerUserId().trim()
        if (owner.isBlank()) return failure(ConversationScheduleFailure.SESSION_MISSING)
        if (request.chatId.isBlank() || !ScheduledMessagePolicy.isValidText(request.text)) {
            return failure(ConversationScheduleFailure.INVALID_REQUEST)
        }
        if (!ScheduledMessagePolicy.canAddMore(backend.listScheduled(owner, request.chatId).size)) {
            return failure(ConversationScheduleFailure.LIMIT_REACHED)
        }
        val item = try {
            backend.addScheduled(owner, request)
        } catch (error: Throwable) {
            return failure(ConversationScheduleFailure.STORAGE, error)
        } ?: return failure(ConversationScheduleFailure.STORAGE)

        return try {
            backend.scheduleJob(item)
            ConversationScheduleResult.Success(item)
        } catch (error: Throwable) {
            runCatching { backend.removeScheduled(owner, item.id) }
            failure(ConversationScheduleFailure.STORAGE, error)
        }
    }

    fun cancel(id: String): ConversationScheduleResult<Unit> {
        val owner = ownerUserId().trim()
        if (owner.isBlank()) return failure(ConversationScheduleFailure.SESSION_MISSING)
        if (id.isBlank()) return failure(ConversationScheduleFailure.INVALID_REQUEST)
        val removed = try {
            backend.removeScheduled(owner, id)
        } catch (error: Throwable) {
            return failure(ConversationScheduleFailure.STORAGE, error)
        }
        if (!removed) return failure(ConversationScheduleFailure.NOT_FOUND)
        return try {
            backend.cancelJob(id)
            ConversationScheduleResult.Success(Unit)
        } catch (error: Throwable) {
            // The row is already gone. A stale Worker safely exits when it cannot load the item.
            ConversationScheduleResult.Success(Unit)
        }
    }

    fun cancelAll(chatId: String): ConversationScheduleResult<Int> {
        return cancelAllForOwner(ownerUserId(), chatId)
    }

    fun cancelAllForOwner(
        ownerUserId: String,
        chatId: String,
    ): ConversationScheduleResult<Int> {
        val owner = ownerUserId.trim()
        if (owner.isBlank()) return failure(ConversationScheduleFailure.SESSION_MISSING)
        if (chatId.isBlank()) return failure(ConversationScheduleFailure.INVALID_REQUEST)
        val removed = try {
            backend.clearScheduled(owner, chatId)
        } catch (error: Throwable) {
            return failure(ConversationScheduleFailure.STORAGE, error)
        }
        removed.forEach { runCatching { backend.cancelJob(it) } }
        return ConversationScheduleResult.Success(removed.size)
    }

    fun reschedule(
        id: String,
        sendAtMillis: Long,
        text: String? = null,
    ): ConversationScheduleResult<ScheduledMessage> {
        val owner = ownerUserId().trim()
        if (owner.isBlank()) return failure(ConversationScheduleFailure.SESSION_MISSING)
        val previous = backend.getScheduled(owner, id)
            ?: return failure(ConversationScheduleFailure.NOT_FOUND)
        val updated = try {
            backend.updateScheduled(owner, id, text, sendAtMillis)
        } catch (error: Throwable) {
            return failure(ConversationScheduleFailure.STORAGE, error)
        } ?: return failure(ConversationScheduleFailure.INVALID_REQUEST)

        return try {
            backend.rescheduleJob(updated)
            ConversationScheduleResult.Success(updated)
        } catch (error: Throwable) {
            runCatching {
                backend.updateScheduled(owner, id, previous.text, previous.sendAtMillis)
                backend.rescheduleJob(previous)
            }
            failure(ConversationScheduleFailure.STORAGE, error)
        }
    }

    /** Pauses the Worker but keeps the row until the durable outbox commit succeeds. */
    fun beginImmediateSend(id: String): ConversationScheduleResult<ScheduledMessage> {
        val owner = ownerUserId().trim()
        if (owner.isBlank()) return failure(ConversationScheduleFailure.SESSION_MISSING)
        val item = backend.getScheduled(owner, id)
            ?: return failure(ConversationScheduleFailure.NOT_FOUND)
        return try {
            backend.cancelJob(id)
            ConversationScheduleResult.Success(item)
        } catch (error: Throwable) {
            failure(ConversationScheduleFailure.STORAGE, error)
        }
    }

    fun completeImmediateSend(id: String) {
        completeImmediateSend(ownerUserId(), id)
    }

    fun completeImmediateSend(ownerUserId: String, id: String) {
        val owner = ownerUserId.trim()
        if (owner.isBlank() || id.isBlank()) return
        runCatching { backend.removeScheduled(owner, id) }
        runCatching { backend.cancelJob(id) }
    }

    fun restoreImmediateSend(id: String) {
        restoreImmediateSend(ownerUserId(), id)
    }

    fun restoreImmediateSend(ownerUserId: String, id: String) {
        val owner = ownerUserId.trim()
        if (owner.isBlank() || id.isBlank()) return
        runCatching {
            backend.getScheduled(owner, id)?.let { backend.scheduleJob(it) }
        }
    }

    fun scheduleReminder(
        request: MessageReminderRequest,
    ): ConversationScheduleResult<MessageReminderStore.MessageReminder> {
        val owner = ownerUserId().trim()
        if (owner.isBlank()) return failure(ConversationScheduleFailure.SESSION_MISSING)
        if (request.chatId.isBlank() || request.messageId.isBlank()) {
            return failure(ConversationScheduleFailure.INVALID_REQUEST)
        }
        val currentTime = now()
        val reminder = MessageReminderStore.MessageReminder(
            id = reminderId(),
            chatId = request.chatId,
            messageId = request.messageId,
            messagePreview = request.messagePreview.trim().take(80),
            remindAtMillis = request.remindAtMillis.coerceIn(
                currentTime + MessageReminderPolicy.MIN_DELAY_MS,
                currentTime + MessageReminderPolicy.MAX_DELAY_MS,
            ),
            createdAtMillis = currentTime,
            ownerUserId = owner,
        )
        return try {
            backend.upsertReminder(reminder)
            backend.scheduleReminderJob(reminder)
            ConversationScheduleResult.Success(reminder)
        } catch (error: Throwable) {
            runCatching { backend.removeReminder(owner, reminder.id) }
            failure(ConversationScheduleFailure.STORAGE, error)
        }
    }

    fun listReminders(chatId: String): List<MessageReminderStore.MessageReminder> {
        val owner = ownerUserId().trim()
        if (owner.isBlank() || chatId.isBlank()) return emptyList()
        val currentTime = now()
        return backend.listReminders(owner)
            .filter { it.chatId == chatId && it.remindAtMillis > currentTime }
            .sortedBy { it.remindAtMillis }
    }

    fun cancelReminder(id: String) {
        val owner = ownerUserId().trim()
        if (owner.isBlank() || id.isBlank()) return
        backend.removeReminder(owner, id)
        runCatching { backend.cancelReminderJob(id) }
    }

    fun clearReminders(chatId: String) {
        clearRemindersForOwner(ownerUserId(), chatId)
    }

    fun clearRemindersForOwner(ownerUserId: String, chatId: String) {
        val owner = ownerUserId.trim()
        if (owner.isBlank() || chatId.isBlank()) return
        val reminders = backend.listReminders(owner).filter { it.chatId == chatId }
        backend.clearReminders(owner, chatId)
        reminders.forEach { runCatching { backend.cancelReminderJob(it.id) } }
    }

    private fun failure(
        reason: ConversationScheduleFailure,
        cause: Throwable? = null,
    ): ConversationScheduleResult.Failure = ConversationScheduleResult.Failure(reason, cause)
}
