package com.maodouchat.scheduling

import com.maodouchat.util.MessageReminderStore
import com.maodouchat.util.ScheduledMessage
import com.maodouchat.util.ScheduledMessagePolicy

data class ChatScheduleCommand(
    val chatId: String,
    val peerUserId: String,
    val text: String,
    val isGroup: Boolean,
    val sessionAvailable: Boolean,
    val blocked: Boolean,
    val sendAtMillis: Long,
    val repeatIntervalMs: Long = 0L,
    val repeatCount: Int = 0,
    val weekdaysOnly: Boolean = false,
)

enum class ChatScheduleRejection {
    DISABLED,
    INVALID_REQUEST,
    SESSION_MISSING,
    BLOCKED,
    LIMIT_REACHED,
    NOT_FOUND,
    STORAGE,
}

sealed interface ChatScheduleMutationOutcome {
    data class Applied(
        val scheduledMessages: List<ScheduledMessage>,
        val effectiveAtMillis: Long? = null,
    ) : ChatScheduleMutationOutcome

    data class Rejected(val reason: ChatScheduleRejection) : ChatScheduleMutationOutcome
}

sealed interface ChatScheduleImmediateOutcome {
    data class Ready(val item: ScheduledMessage) : ChatScheduleImmediateOutcome
    data class Rejected(val reason: ChatScheduleRejection) : ChatScheduleImmediateOutcome
}

sealed interface ChatReminderOutcome {
    data class Scheduled(val reminder: MessageReminderStore.MessageReminder) : ChatReminderOutcome
    data class Rejected(val reason: ChatScheduleRejection) : ChatReminderOutcome
}

/**
 * Screen-independent application controller for scheduled messages and reminders.
 * It owns admission and two-phase immediate-send state; UI code only projects typed outcomes.
 */
class ChatScheduleController(
    private val coordinator: ConversationScheduleCoordinator,
    private val scheduledMessagesEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun listScheduled(chatId: String): List<ScheduledMessage> = coordinator.listScheduled(chatId)

    fun queue(command: ChatScheduleCommand): ChatScheduleMutationOutcome {
        if (!scheduledMessagesEnabled()) return rejected(ChatScheduleRejection.DISABLED)
        if (!command.sessionAvailable) return rejected(ChatScheduleRejection.SESSION_MISSING)
        if (command.blocked) return rejected(ChatScheduleRejection.BLOCKED)
        if (
            command.chatId.isBlank() ||
            !ScheduledMessagePolicy.isValidText(command.text)
        ) return rejected(ChatScheduleRejection.INVALID_REQUEST)

        return when (
            val result = coordinator.queue(
                ScheduledMessageRequest(
                    chatId = command.chatId,
                    peerUserId = if (command.isGroup) "" else command.peerUserId,
                    text = command.text,
                    sendAtMillis = command.sendAtMillis,
                    isGroup = command.isGroup,
                    repeatIntervalMs = command.repeatIntervalMs,
                    repeatCount = command.repeatCount,
                    weekdaysOnly = command.weekdaysOnly,
                ),
            )
        ) {
            is ConversationScheduleResult.Success -> ChatScheduleMutationOutcome.Applied(
                scheduledMessages = coordinator.listScheduled(command.chatId),
                effectiveAtMillis = result.value.sendAtMillis,
            )
            is ConversationScheduleResult.Failure -> rejected(result.reason.toRejection())
        }
    }

    fun queueAfter(command: ChatScheduleCommand, delayMs: Long): ChatScheduleMutationOutcome =
        queue(
            command.copy(
                sendAtMillis = clock() + delayMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS),
            ),
        )

    fun cancel(chatId: String, id: String): ChatScheduleMutationOutcome = when (val result = coordinator.cancel(id)) {
        is ConversationScheduleResult.Success -> applied(chatId)
        is ConversationScheduleResult.Failure -> rejected(result.reason.toRejection())
    }

    fun cancelAll(chatId: String): ChatScheduleMutationOutcome = when (val result = coordinator.cancelAll(chatId)) {
        is ConversationScheduleResult.Success -> applied(chatId)
        is ConversationScheduleResult.Failure -> rejected(result.reason.toRejection())
    }

    fun reschedule(
        chatId: String,
        id: String,
        sendAtMillis: Long,
        text: String? = null,
    ): ChatScheduleMutationOutcome = when (val result = coordinator.reschedule(id, sendAtMillis, text)) {
        is ConversationScheduleResult.Success -> ChatScheduleMutationOutcome.Applied(
            scheduledMessages = coordinator.listScheduled(chatId),
            effectiveAtMillis = result.value.sendAtMillis,
        )
        is ConversationScheduleResult.Failure -> rejected(result.reason.toRejection())
    }

    fun rescheduleAfter(
        chatId: String,
        id: String,
        delayMs: Long,
        text: String? = null,
    ): ChatScheduleMutationOutcome = reschedule(
        chatId = chatId,
        id = id,
        sendAtMillis = clock() + delayMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS),
        text = text,
    )

    fun beginImmediateSend(id: String): ChatScheduleImmediateOutcome = when (
        val result = coordinator.beginImmediateSend(id)
    ) {
        is ConversationScheduleResult.Success -> ChatScheduleImmediateOutcome.Ready(result.value)
        is ConversationScheduleResult.Failure -> ChatScheduleImmediateOutcome.Rejected(result.reason.toRejection())
    }

    fun completeImmediateSend(ownerUserId: String, id: String) =
        coordinator.completeImmediateSend(ownerUserId, id)

    fun restoreImmediateSend(ownerUserId: String, id: String) =
        coordinator.restoreImmediateSend(ownerUserId, id)

    fun scheduleReminder(request: MessageReminderRequest): ChatReminderOutcome = when (
        val result = coordinator.scheduleReminder(request)
    ) {
        is ConversationScheduleResult.Success -> ChatReminderOutcome.Scheduled(result.value)
        is ConversationScheduleResult.Failure -> ChatReminderOutcome.Rejected(result.reason.toRejection())
    }

    fun listReminders(chatId: String): List<MessageReminderStore.MessageReminder> =
        coordinator.listReminders(chatId)

    fun cancelReminder(id: String) = coordinator.cancelReminder(id)

    fun clearReminders(chatId: String) = coordinator.clearReminders(chatId)

    private fun applied(chatId: String) = ChatScheduleMutationOutcome.Applied(
        scheduledMessages = coordinator.listScheduled(chatId),
    )

    private fun rejected(reason: ChatScheduleRejection) =
        ChatScheduleMutationOutcome.Rejected(reason)
}

private fun ConversationScheduleFailure.toRejection(): ChatScheduleRejection = when (this) {
    ConversationScheduleFailure.SESSION_MISSING -> ChatScheduleRejection.SESSION_MISSING
    ConversationScheduleFailure.INVALID_REQUEST -> ChatScheduleRejection.INVALID_REQUEST
    ConversationScheduleFailure.LIMIT_REACHED -> ChatScheduleRejection.LIMIT_REACHED
    ConversationScheduleFailure.NOT_FOUND -> ChatScheduleRejection.NOT_FOUND
    ConversationScheduleFailure.STORAGE -> ChatScheduleRejection.STORAGE
}
