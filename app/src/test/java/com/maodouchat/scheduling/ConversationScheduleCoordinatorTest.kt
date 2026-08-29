package com.maodouchat.scheduling

import com.maodouchat.util.MessageReminderStore
import com.maodouchat.util.ScheduledMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScheduleCoordinatorTest {
    @Test
    fun `queue binds item to owner and schedules only after storage`() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)

        val result = coordinator.queue(request())

        assertTrue(result is ConversationScheduleResult.Success)
        assertEquals("owner-1", backend.lastAddOwner)
        assertEquals(listOf("add:sch-1", "schedule:sch-1"), backend.events)
        assertEquals(1, backend.scheduled.size)
    }

    @Test
    fun `queue rolls storage back when worker scheduling fails`() {
        val backend = FakeBackend(scheduleFailuresRemaining = 1)
        val coordinator = coordinator(backend)

        val result = coordinator.queue(request())

        assertEquals(
            ConversationScheduleFailure.STORAGE,
            (result as ConversationScheduleResult.Failure).reason,
        )
        assertTrue(backend.scheduled.isEmpty())
        assertEquals(listOf("add:sch-1", "schedule:sch-1", "remove:sch-1"), backend.events)
    }

    @Test
    fun `reschedule restores previous row and job when replacement fails`() {
        val backend = FakeBackend(rescheduleFailuresRemaining = 1)
        val original = scheduled(id = "existing", text = "old", sendAt = 100L)
        backend.scheduled += original
        val coordinator = coordinator(backend)

        val result = coordinator.reschedule("existing", sendAtMillis = 900L, text = "new")

        assertEquals(
            ConversationScheduleFailure.STORAGE,
            (result as ConversationScheduleResult.Failure).reason,
        )
        assertEquals(original, backend.scheduled.single())
        assertTrue(backend.events.contains("reschedule:existing:900"))
        assertTrue(backend.events.contains("reschedule:existing:100"))
    }

    @Test
    fun `immediate send pauses job and removes row only after durable commit`() {
        val backend = FakeBackend()
        backend.scheduled += scheduled(id = "existing")
        val coordinator = coordinator(backend)

        val begun = coordinator.beginImmediateSend("existing")

        assertTrue(begun is ConversationScheduleResult.Success)
        assertEquals(1, backend.scheduled.size)
        assertTrue(backend.cancelledJobs.contains("existing"))

        coordinator.completeImmediateSend("existing")

        assertTrue(backend.scheduled.isEmpty())
    }

    @Test
    fun `failed immediate send restores paused worker without deleting row`() {
        val backend = FakeBackend()
        backend.scheduled += scheduled(id = "existing")
        val coordinator = coordinator(backend)

        coordinator.beginImmediateSend("existing")
        coordinator.restoreImmediateSend("existing")

        assertEquals(1, backend.scheduled.size)
        assertTrue(backend.events.contains("schedule:existing"))
    }

    @Test
    fun `durable completion removes original owner row after account switch`() {
        val backend = FakeBackend()
        backend.scheduled += scheduled(id = "existing")
        backend.scheduled += scheduled(id = "existing", ownerUserId = "owner-2")
        var liveOwner = "owner-1"
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { liveOwner },
            backend = backend,
        )

        val begun = coordinator.beginImmediateSend("existing") as ConversationScheduleResult.Success
        liveOwner = "owner-2"
        coordinator.completeImmediateSend(begun.value.ownerUserId, begun.value.id)

        assertEquals(listOf("owner-2"), backend.scheduled.map { it.ownerUserId })
    }

    @Test
    fun `reminder scheduler failure removes stored reminder`() {
        val backend = FakeBackend(reminderScheduleFailuresRemaining = 1)
        val coordinator = coordinator(backend, now = 1_000L)

        val result = coordinator.scheduleReminder(
            MessageReminderRequest(
                chatId = "chat-1",
                messageId = "message-1",
                messagePreview = "hello",
                remindAtMillis = Long.MAX_VALUE,
            )
        )

        assertEquals(
            ConversationScheduleFailure.STORAGE,
            (result as ConversationScheduleResult.Failure).reason,
        )
        assertTrue(backend.reminders.isEmpty())
        assertEquals("owner-1", backend.lastReminderOwner)
    }

    @Test
    fun `missing session never touches backend`() {
        val backend = FakeBackend()
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "" },
            backend = backend,
        )

        val result = coordinator.queue(request())

        assertEquals(
            ConversationScheduleFailure.SESSION_MISSING,
            (result as ConversationScheduleResult.Failure).reason,
        )
        assertTrue(backend.events.isEmpty())
        assertNull(backend.lastAddOwner)
    }

    @Test
    fun `chat controller rejects missing live session before coordinator storage`() {
        val backend = FakeBackend()
        val controller = ChatScheduleController(
            coordinator = coordinator(backend),
            scheduledMessagesEnabled = { true },
        )

        val result = controller.queue(command(sessionAvailable = false))

        assertEquals(
            ChatScheduleRejection.SESSION_MISSING,
            (result as ChatScheduleMutationOutcome.Rejected).reason,
        )
        assertTrue(backend.events.isEmpty())
    }

    @Test
    fun `chat controller rejects disabled and blocked commands without storage writes`() {
        val disabledBackend = FakeBackend()
        val disabled = ChatScheduleController(
            coordinator = coordinator(disabledBackend),
            scheduledMessagesEnabled = { false },
        ).queue(command())
        assertEquals(
            ChatScheduleRejection.DISABLED,
            (disabled as ChatScheduleMutationOutcome.Rejected).reason,
        )
        assertTrue(disabledBackend.events.isEmpty())

        val blockedBackend = FakeBackend()
        val blocked = ChatScheduleController(
            coordinator = coordinator(blockedBackend),
            scheduledMessagesEnabled = { true },
        ).queue(command(blocked = true))
        assertEquals(
            ChatScheduleRejection.BLOCKED,
            (blocked as ChatScheduleMutationOutcome.Rejected).reason,
        )
        assertTrue(blockedBackend.events.isEmpty())
    }

    @Test
    fun `chat controller returns authoritative scheduled snapshot after queue`() {
        val backend = FakeBackend()
        val controller = ChatScheduleController(
            coordinator = coordinator(backend),
            scheduledMessagesEnabled = { true },
        )

        val result = controller.queue(command(isGroup = true)) as ChatScheduleMutationOutcome.Applied

        assertEquals(100_000L, result.effectiveAtMillis)
        assertEquals(listOf("sch-1"), result.scheduledMessages.map { it.id })
        assertEquals("", result.scheduledMessages.single().peerUserId)
        assertTrue(result.scheduledMessages.single().isGroup)
    }

    @Test
    fun `chat controller immediate send lease survives live owner changes`() {
        val backend = FakeBackend()
        backend.scheduled += scheduled(id = "existing")
        var owner = "owner-1"
        val controller = ChatScheduleController(
            coordinator = ConversationScheduleCoordinator(
                ownerUserId = { owner },
                backend = backend,
            ),
            scheduledMessagesEnabled = { true },
        )

        val ready = controller.beginImmediateSend("existing") as ChatScheduleImmediateOutcome.Ready
        owner = "owner-2"
        controller.completeImmediateSend(ready.item.ownerUserId, ready.item.id)

        assertTrue(backend.scheduled.isEmpty())
    }

    private fun coordinator(
        backend: FakeBackend,
        now: Long = 1_000L,
    ) = ConversationScheduleCoordinator(
        ownerUserId = { "owner-1" },
        backend = backend,
        now = { now },
        reminderId = { "reminder-1" },
    )

    private fun request() = ScheduledMessageRequest(
        chatId = "chat-1",
        peerUserId = "peer-1",
        text = "hello",
        sendAtMillis = 100_000L,
        isGroup = false,
    )

    private fun command(
        sessionAvailable: Boolean = true,
        blocked: Boolean = false,
        isGroup: Boolean = false,
    ) = ChatScheduleCommand(
        chatId = "chat-1",
        peerUserId = "peer-1",
        text = "hello",
        isGroup = isGroup,
        sessionAvailable = sessionAvailable,
        blocked = blocked,
        sendAtMillis = 100_000L,
    )

    private fun scheduled(
        id: String,
        text: String = "hello",
        sendAt: Long = 100_000L,
        ownerUserId: String = "owner-1",
    ) = ScheduledMessage(
        id = id,
        chatId = "chat-1",
        peerUserId = "peer-1",
        text = text,
        sendAtMillis = sendAt,
        createdAtMillis = 1L,
        ownerUserId = ownerUserId,
    )

    private class FakeBackend(
        var scheduleFailuresRemaining: Int = 0,
        var rescheduleFailuresRemaining: Int = 0,
        var reminderScheduleFailuresRemaining: Int = 0,
    ) : ConversationScheduleBackend {
        val scheduled = mutableListOf<ScheduledMessage>()
        val reminders = mutableListOf<MessageReminderStore.MessageReminder>()
        val cancelledJobs = mutableListOf<String>()
        val events = mutableListOf<String>()
        var lastAddOwner: String? = null
        var lastReminderOwner: String? = null

        override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> =
            scheduled.filter { it.ownerUserId == ownerUserId }

        override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> =
            scheduled.filter { it.ownerUserId == ownerUserId && it.chatId == chatId }

        override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? =
            scheduled.firstOrNull { it.ownerUserId == ownerUserId && it.id == id }

        override fun addScheduled(
            ownerUserId: String,
            request: ScheduledMessageRequest,
        ): ScheduledMessage {
            lastAddOwner = ownerUserId
            val item = ScheduledMessage(
                id = "sch-1",
                chatId = request.chatId,
                peerUserId = request.peerUserId,
                text = request.text,
                sendAtMillis = request.sendAtMillis,
                createdAtMillis = 1L,
                isGroup = request.isGroup,
                ownerUserId = ownerUserId,
                repeatIntervalMs = request.repeatIntervalMs,
                repeatCount = request.repeatCount,
                weekdaysOnly = request.weekdaysOnly,
            )
            scheduled += item
            events += "add:${item.id}"
            return item
        }

        override fun updateScheduled(
            ownerUserId: String,
            id: String,
            text: String?,
            sendAtMillis: Long?,
        ): ScheduledMessage? {
            val index = scheduled.indexOfFirst { it.ownerUserId == ownerUserId && it.id == id }
            if (index < 0) return null
            val updated = scheduled[index].copy(
                text = text ?: scheduled[index].text,
                sendAtMillis = sendAtMillis ?: scheduled[index].sendAtMillis,
            )
            scheduled[index] = updated
            events += "update:$id:${updated.sendAtMillis}"
            return updated
        }

        override fun removeScheduled(ownerUserId: String, id: String): Boolean {
            events += "remove:$id"
            return scheduled.removeAll { it.ownerUserId == ownerUserId && it.id == id }
        }

        override fun clearScheduled(ownerUserId: String, chatId: String): List<String> {
            val ids = scheduled
                .filter { it.ownerUserId == ownerUserId && it.chatId == chatId }
                .map { it.id }
            scheduled.removeAll { it.ownerUserId == ownerUserId && it.chatId == chatId }
            return ids
        }

        override fun scheduleJob(item: ScheduledMessage) {
            events += "schedule:${item.id}"
            if (scheduleFailuresRemaining-- > 0) error("schedule failed")
        }

        override fun cancelJob(id: String) {
            cancelledJobs += id
            events += "cancel:$id"
        }

        override fun rescheduleJob(item: ScheduledMessage) {
            events += "reschedule:${item.id}:${item.sendAtMillis}"
            if (rescheduleFailuresRemaining-- > 0) error("reschedule failed")
        }

        override fun listReminders(ownerUserId: String): List<MessageReminderStore.MessageReminder> =
            reminders.filter { it.ownerUserId == ownerUserId }

        override fun upsertReminder(reminder: MessageReminderStore.MessageReminder) {
            lastReminderOwner = reminder.ownerUserId
            reminders.removeAll { it.id == reminder.id }
            reminders += reminder
        }

        override fun removeReminder(ownerUserId: String, id: String) {
            reminders.removeAll { it.ownerUserId == ownerUserId && it.id == id }
        }

        override fun clearReminders(ownerUserId: String, chatId: String) {
            reminders.removeAll { it.ownerUserId == ownerUserId && it.chatId == chatId }
        }

        override fun scheduleReminderJob(reminder: MessageReminderStore.MessageReminder) {
            if (reminderScheduleFailuresRemaining-- > 0) error("reminder schedule failed")
        }

        override fun cancelReminderJob(id: String) = Unit
    }
}
