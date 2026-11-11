package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatScheduleController
import com.maodouchat.core.model.ConversationScheduleBackend
import com.maodouchat.core.model.ConversationScheduleCoordinator
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReminderControllerTest {

    private class FakeScheduleBackend : ConversationScheduleBackend {
        val reminders = mutableListOf<MessageReminder>()
        val scheduledJobs = mutableListOf<String>()
        val cancelledJobs = mutableListOf<String>()

        override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> = emptyList()
        override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> = emptyList()
        override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? = null
        override fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage? = null
        override fun updateScheduled(ownerUserId: String, id: String, text: String?, sendAtMillis: Long?): ScheduledMessage? = null
        override fun removeScheduled(ownerUserId: String, id: String): Boolean = false
        override fun clearScheduled(ownerUserId: String, chatId: String): List<String> = emptyList()
        override fun scheduleJob(item: ScheduledMessage) {}
        override fun cancelJob(id: String) {}
        override fun rescheduleJob(item: ScheduledMessage) {}
        override fun listReminders(ownerUserId: String): List<MessageReminder> = reminders.toList()
        override fun upsertReminder(reminder: MessageReminder) {
            reminders.removeAll { it.id == reminder.id }
            reminders += reminder
        }
        override fun removeReminder(ownerUserId: String, id: String) {
            reminders.removeAll { it.id == id }
        }
        override fun clearReminders(ownerUserId: String, chatId: String) {
            reminders.removeAll { it.chatId == chatId }
        }
        override fun scheduleReminderJob(reminder: MessageReminder) {
            scheduledJobs += reminder.id
        }
        override fun cancelReminderJob(id: String) {
            cancelledJobs += id
        }
    }

    private class Harness(
        var ownerId: String = "u1",
        var activeChatId: String = "c1",
        var chatId: String = "c1",
    ) {
        val uiState = MutableStateFlow(ChatDetailUiState())
        val backend = FakeScheduleBackend()
        private var reminderSeq = 0
        private val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { ownerId },
            backend = backend,
            now = { 1000L },
            reminderId = { "mr-${++reminderSeq}" },
        )
        val controller = ChatReminderController(
            chatScheduleController = ChatScheduleController(coordinator, scheduledMessagesEnabled = { true }),
            uiState = uiState,
            text = { message, args ->
                "[${message.name}]" + if (args.isEmpty()) "" else " " + args.joinToString(",")
            },
            relativeTimeText = { remindAt, _ -> "rel:$remindAt" },
            activeChatId = { activeChatId },
            chatId = chatId,
        )
    }

    private fun message(id: String = "m1", content: String = "hello") =
        Message(id = id, chatId = "c1", senderId = "u1", content = content)

    @Test
    fun `schedule success warns with relative time`() {
        val h = Harness()
        h.controller.scheduleMessageReminder(message(), remindAtMillis = 900_000L)

        val stored = h.backend.reminders.single()
        assertEquals("m1", stored.messageId)
        assertEquals("hello", stored.messagePreview)
        assertEquals(listOf("mr-1"), h.backend.scheduledJobs)
        assertEquals("[REMINDER_SCHEDULED] rel:${stored.remindAtMillis}", h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `blank chat does nothing`() {
        val h = Harness(activeChatId = "", chatId = "")
        h.controller.scheduleMessageReminder(message(), remindAtMillis = 900_000L)

        assertTrue(h.backend.reminders.isEmpty())
        assertNull(h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `session missing warns expired`() {
        val h = Harness(ownerId = "")
        h.controller.scheduleMessageReminder(message(), remindAtMillis = 900_000L)

        assertEquals("[SESSION_EXPIRED]", h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `invalid request warns schedule failed`() {
        val h = Harness()
        h.controller.scheduleMessageReminder(message(id = ""), remindAtMillis = 900_000L)

        assertEquals("[SCHEDULE_FAILED]", h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `list and cancel round-trip`() {
        val h = Harness()
        h.controller.scheduleMessageReminder(message(), remindAtMillis = 900_000L)
        assertEquals(1, h.controller.listRemindersForChat("c1").size)

        h.controller.cancelReminder("mr-1")
        assertEquals(emptyList<MessageReminder>(), h.controller.listRemindersForChat("c1"))
        assertEquals(listOf("mr-1"), h.backend.cancelledJobs)
    }

    @Test
    fun `clear removes only this chat`() {
        val h = Harness()
        h.controller.scheduleMessageReminder(message(), remindAtMillis = 900_000L)
        h.activeChatId = "c2"
        h.controller.scheduleMessageReminder(message(id = "m2"), remindAtMillis = 900_000L)
        assertEquals(1, h.controller.listRemindersForChat("c1").size)
        assertEquals(1, h.controller.listRemindersForChat("c2").size)

        h.controller.clearRemindersForChat("c1")
        assertEquals(emptyList<MessageReminder>(), h.controller.listRemindersForChat("c1"))
        assertEquals(1, h.controller.listRemindersForChat("c2").size)
    }
}
