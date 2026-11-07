package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

class ChatScheduleControllerTest {

    @Test
    fun queueRejectedWhenDisabled() {
        val controller = controller(enabled = false)
        val outcome = controller.queue(command())
        assertIs<ChatScheduleMutationOutcome.Rejected>(outcome)
        assertEquals(ChatScheduleRejection.DISABLED, outcome.reason)
    }

    @Test
    fun queueRejectedWhenSessionMissing() {
        val controller = controller()
        val outcome = controller.queue(command(sessionAvailable = false))
        assertIs<ChatScheduleMutationOutcome.Rejected>(outcome)
        assertEquals(ChatScheduleRejection.SESSION_MISSING, outcome.reason)
    }

    @Test
    fun queueRejectedWhenBlocked() {
        val controller = controller()
        val outcome = controller.queue(command(blocked = true))
        assertIs<ChatScheduleMutationOutcome.Rejected>(outcome)
        assertEquals(ChatScheduleRejection.BLOCKED, outcome.reason)
    }

    @Test
    fun queueAppliesAndLists() {
        val controller = controller()
        val outcome = controller.queue(command(text = "hello", sendAtMillis = 5000L))
        assertIs<ChatScheduleMutationOutcome.Applied>(outcome)
        assertEquals(5000L, outcome.effectiveAtMillis)
        assertEquals(1, outcome.scheduledMessages.size)
        assertEquals("hello", outcome.scheduledMessages.single().text)
    }

    @Test
    fun queueAfterFloorsDelayAtMinDelay() {
        val controller = controller(clock = 1000L)
        val outcome = controller.queueAfter(command(sendAtMillis = 0L), delayMs = 1000L)
        assertIs<ChatScheduleMutationOutcome.Applied>(outcome)
        assertEquals(1000L + ScheduledMessagePolicy.MIN_DELAY_MS, outcome.effectiveAtMillis)
    }

    @Test
    fun cancelAndImmediateSendAndReminder() {
        val backend = FakeScheduleBackend()
        val controller = controller(backend = backend)
        val queued = controller.queue(command(sendAtMillis = 5000L))
        assertIs<ChatScheduleMutationOutcome.Applied>(queued)
        val id = queued.scheduledMessages.single().id
        val ready = controller.beginImmediateSend(id)
        assertIs<ChatScheduleImmediateOutcome.Ready>(ready)
        assertEquals(id, ready.item.id)
        controller.completeImmediateSend("u1", id)
        assertEquals(emptyList<ScheduledMessage>(), controller.listScheduled("chat-1"))
        val reminder = controller.scheduleReminder(
            MessageReminderRequest(
                chatId = "chat-1", messageId = "m1",
                messagePreview = "hi", remindAtMillis = 900_000L,
            ),
        )
        assertIs<ChatReminderOutcome.Scheduled>(reminder)
        assertEquals(1, controller.listReminders("chat-1").size)
        controller.cancelReminder(reminder.reminder.id)
        assertEquals(emptyList<MessageReminder>(), controller.listReminders("chat-1"))
        val cancelled = controller.cancel("chat-1", "missing")
        assertIs<ChatScheduleMutationOutcome.Rejected>(cancelled)
        assertEquals(ChatScheduleRejection.NOT_FOUND, cancelled.reason)
    }

    private fun command(
        sessionAvailable: Boolean = true,
        blocked: Boolean = false,
        text: String = "hi",
        sendAtMillis: Long = 5000L,
    ) = ChatScheduleCommand(
        chatId = "chat-1",
        peerUserId = "u2",
        text = text,
        isGroup = false,
        sessionAvailable = sessionAvailable,
        blocked = blocked,
        sendAtMillis = sendAtMillis,
    )

    private fun controller(
        enabled: Boolean = true,
        clock: Long = 1000L,
        backend: ConversationScheduleBackend = FakeScheduleBackend(),
    ): ChatScheduleController {
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "u1" },
            backend = backend,
            now = { clock },
        )
        return ChatScheduleController(
            coordinator = coordinator,
            scheduledMessagesEnabled = { enabled },
            clock = { clock },
        )
    }

    private class FakeScheduleBackend : ConversationScheduleBackend {
        private val items = mutableListOf<ScheduledMessage>()
        private val reminders = mutableListOf<MessageReminder>()
        private val jobs = mutableSetOf<String>()

        override fun listAllScheduled(ownerUserId: String) = items.toList()

        override fun listScheduled(ownerUserId: String, chatId: String) =
            items.filter { it.chatId == chatId }

        override fun getScheduled(ownerUserId: String, id: String) =
            items.firstOrNull { it.id == id }

        override fun addScheduled(
            ownerUserId: String,
            request: ScheduledMessageRequest,
        ): ScheduledMessage {
            val item = ScheduledMessage(
                id = "sched-${items.size + 1}",
                chatId = request.chatId,
                peerUserId = request.peerUserId,
                text = request.text,
                sendAtMillis = request.sendAtMillis,
                createdAtMillis = 1000L,
                isGroup = request.isGroup,
            )
            items += item
            return item
        }

        override fun updateScheduled(
            ownerUserId: String,
            id: String,
            text: String?,
            sendAtMillis: Long?,
        ) = getScheduled(ownerUserId, id)

        override fun removeScheduled(ownerUserId: String, id: String) =
            items.removeIf { it.id == id }

        override fun clearScheduled(ownerUserId: String, chatId: String): List<String> {
            val removed = items.filter { it.chatId == chatId }.map { it.id }
            items.removeIf { it.chatId == chatId }
            return removed
        }

        override fun scheduleJob(item: ScheduledMessage) {
            jobs += item.id
        }

        override fun cancelJob(id: String) {
            jobs -= id
        }

        override fun rescheduleJob(item: ScheduledMessage) {
            jobs += item.id
        }

        override fun listReminders(ownerUserId: String) = reminders.toList()

        override fun upsertReminder(reminder: MessageReminder) {
            reminders.removeAll { it.id == reminder.id }
            reminders += reminder
        }

        override fun removeReminder(ownerUserId: String, id: String) {
            reminders.removeIf { it.id == id }
        }

        override fun clearReminders(ownerUserId: String, chatId: String) {
            reminders.removeIf { it.chatId == chatId }
        }

        override fun scheduleReminderJob(reminder: MessageReminder) = Unit

        override fun cancelReminderJob(id: String) = Unit
    }
}
