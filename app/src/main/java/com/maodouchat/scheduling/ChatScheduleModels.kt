package com.maodouchat.scheduling

import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.ChatScheduleRejection
import com.maodouchat.util.ScheduledMessage

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
    data class Scheduled(val reminder: MessageReminder) : ChatReminderOutcome
    data class Rejected(val reason: ChatScheduleRejection) : ChatReminderOutcome
}
