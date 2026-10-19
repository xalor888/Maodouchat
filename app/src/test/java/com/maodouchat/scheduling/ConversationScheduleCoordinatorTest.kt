package com.maodouchat.scheduling

import com.maodouchat.util.MessageReminder
import com.maodouchat.util.ScheduledMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScheduleCoordinatorTest {

    private class FakeBackend : ConversationScheduleBackend {
        val scheduled = mutableMapOf<String, ScheduledMessage>()
        val scheduledJobs = mutableSetOf<String>()
        val reminders = mutableMapOf<String, MessageReminder>()
        val reminderJobs = mutableSetOf<String>()
        var failAddScheduled: Throwable? = null
        var failScheduleJob: Throwable? = null
        var failCancelJob: Throwable? = null
        var failRescheduleJob: Throwable? = null

        override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> =
            scheduled.values.filter { it.ownerUserId == ownerUserId }

        override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> =
            scheduled.values.filter { it.ownerUserId == ownerUserId && it.chatId == chatId }

        override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? =
            scheduled[id]?.takeIf { it.ownerUserId == ownerUserId }

        override fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage? {
            failAddScheduled?.let { throw it }
            val item = ScheduledMessage(
                id = "sch_${scheduled.size + 1}",
                chatId = request.chatId,
                peerUserId = request.peerUserId,
                text = request.text,
                sendAtMillis = request.sendAtMillis,
                createdAtMillis = 1000L,
                isGroup = request.isGroup,
                ownerUserId = ownerUserId,
                repeatIntervalMs = request.repeatIntervalMs,
                repeatCount = request.repeatCount,
                weekdaysOnly = request.weekdaysOnly,
            )
            scheduled[item.id] = item
            return item
        }

        override fun updateScheduled(
            ownerUserId: String,
            id: String,
            text: String?,
            sendAtMillis: Long?,
        ): ScheduledMessage? {
            val current = getScheduled(ownerUserId, id) ?: return null
            val updated = current.copy(
                text = text ?: current.text,
                sendAtMillis = sendAtMillis ?: current.sendAtMillis,
            )
            scheduled[id] = updated
            return updated
        }

        override fun removeScheduled(ownerUserId: String, id: String): Boolean {
            val item = scheduled[id]
            if (item != null && item.ownerUserId == ownerUserId) {
                scheduled.remove(id)
                return true
            }
            return false
        }

        override fun clearScheduled(ownerUserId: String, chatId: String): List<String> {
            val toRemove = scheduled.values.filter { it.ownerUserId == ownerUserId && it.chatId == chatId }.map { it.id }
            toRemove.forEach { scheduled.remove(it) }
            return toRemove
        }

        override fun scheduleJob(item: ScheduledMessage) {
            failScheduleJob?.let { throw it }
            scheduledJobs.add(item.id)
        }

        override fun cancelJob(id: String) {
            failCancelJob?.let { throw it }
            scheduledJobs.remove(id)
        }

        override fun rescheduleJob(item: ScheduledMessage) {
            failRescheduleJob?.let { throw it }
            scheduledJobs.add(item.id)
        }

        override fun listReminders(ownerUserId: String): List<MessageReminder> =
            reminders.values.filter { it.ownerUserId == ownerUserId }

        override fun upsertReminder(reminder: MessageReminder) {
            reminders[reminder.id] = reminder
        }

        override fun removeReminder(ownerUserId: String, id: String) {
            reminders.remove(id)
        }

        override fun clearReminders(ownerUserId: String, chatId: String) {
            val toRemove = reminders.values.filter { it.ownerUserId == ownerUserId && it.chatId == chatId }.map { it.id }
            toRemove.forEach { reminders.remove(it) }
        }

        override fun scheduleReminderJob(reminder: MessageReminder) {
            reminderJobs.add(reminder.id)
        }

        override fun cancelReminderJob(id: String) {
            reminderJobs.remove(id)
        }
    }

    @Test
    fun `two-phase immediate send maintains row until completion or restoration`() {
        val backend = FakeBackend()
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "user_1" },
            backend = backend,
            now = { 10_000L },
        )

        val queueResult = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat_1",
                peerUserId = "peer_1",
                text = "Test send now",
                sendAtMillis = 60_000L,
                isGroup = false,
            )
        )
        assertTrue(queueResult is ConversationScheduleResult.Success)
        val item = (queueResult as ConversationScheduleResult.Success).value

        assertTrue("Job should be scheduled initially", backend.scheduledJobs.contains(item.id))
        assertNotNull(backend.scheduled[item.id])

        // 1. Begin immediate send: cancels worker job, but preserves row in backend
        val beginResult = coordinator.beginImmediateSend(item.id)
        assertTrue(beginResult is ConversationScheduleResult.Success)
        assertTrue("Job should be paused/cancelled", !backend.scheduledJobs.contains(item.id))
        assertNotNull("Row MUST remain during staging phase", backend.scheduled[item.id])

        // 2. Failure scenario -> restoreImmediateSend: job is re-scheduled, row stays
        coordinator.restoreImmediateSend(item.id)
        assertTrue("Job should be re-scheduled on failure", backend.scheduledJobs.contains(item.id))
        assertNotNull("Row remains intact", backend.scheduled[item.id])

        // 3. Success scenario -> begin again, then completeImmediateSend: deletes row and job
        coordinator.beginImmediateSend(item.id)
        coordinator.completeImmediateSend(item.id)
        assertNull("Row should be deleted after outbox commit", backend.scheduled[item.id])
        assertTrue("Job should be cancelled", !backend.scheduledJobs.contains(item.id))
    }

    @Test
    fun `reminder lifecycle clamps within valid range and cleans up on cancel`() {
        val backend = FakeBackend()
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "user_1" },
            backend = backend,
            now = { 100_000L },
            reminderId = { "rem_1" },
        )

        // Request reminder 5 minutes ahead (300,000 ms)
        val result = coordinator.scheduleReminder(
            MessageReminderRequest(
                chatId = "chat_1",
                messageId = "msg_1",
                messagePreview = "Reminder text",
                remindAtMillis = 400_000L,
            )
        )
        assertTrue(result is ConversationScheduleResult.Success)
        val reminder = (result as ConversationScheduleResult.Success).value

        assertEquals("rem_1", reminder.id)
        assertEquals("chat_1", reminder.chatId)
        assertTrue(backend.reminderJobs.contains("rem_1"))

        val activeList = coordinator.listReminders("chat_1")
        assertEquals(1, activeList.size)
        assertEquals("rem_1", activeList[0].id)

        coordinator.cancelReminder("rem_1")
        assertEquals(0, coordinator.listReminders("chat_1").size)
        assertTrue(!backend.reminderJobs.contains("rem_1"))
    }

    @Test
    fun `queue failure when backend add throws reports STORAGE with cause and no job scheduled`() {
        val backend = FakeBackend()
        backend.failAddScheduled = RuntimeException("db down")
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "user_1" },
            backend = backend,
            now = { 10_000L },
        )

        val result = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat_1",
                peerUserId = "peer_1",
                text = "Never persisted",
                sendAtMillis = 60_000L,
                isGroup = false,
            )
        )
        assertTrue(result is ConversationScheduleResult.Failure)
        val failure = result as ConversationScheduleResult.Failure
        assertEquals(ConversationScheduleFailure.STORAGE, failure.reason)
        assertTrue(failure.cause is RuntimeException)
        assertTrue("Nothing persisted when add failed", backend.scheduled.isEmpty())
        assertTrue("No job scheduled when add failed", backend.scheduledJobs.isEmpty())
    }

    @Test
    fun `queue failure when scheduleJob throws removes the persisted row and reports STORAGE`() {
        val backend = FakeBackend()
        backend.failScheduleJob = RuntimeException("worker gone")
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "user_1" },
            backend = backend,
            now = { 10_000L },
        )

        val result = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat_1",
                peerUserId = "peer_1",
                text = "Row must not leak",
                sendAtMillis = 60_000L,
                isGroup = false,
            )
        )
        assertTrue(result is ConversationScheduleResult.Failure)
        assertEquals(
            ConversationScheduleFailure.STORAGE,
            (result as ConversationScheduleResult.Failure).reason,
        )
        assertTrue("Compensating remove must clean the row", backend.scheduled.isEmpty())
    }

    @Test
    fun `blank owner makes queue and cancel fail SESSION_MISSING with zero side effects`() {
        val backend = FakeBackend()
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "   " },
            backend = backend,
            now = { 10_000L },
        )

        val queueResult = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat_1",
                peerUserId = "peer_1",
                text = "No session",
                sendAtMillis = 60_000L,
                isGroup = false,
            )
        )
        assertEquals(
            ConversationScheduleFailure.SESSION_MISSING,
            (queueResult as ConversationScheduleResult.Failure).reason,
        )
        assertTrue("No row may be written without an owner", backend.scheduled.isEmpty())

        val cancelResult = coordinator.cancel("sch_1")
        assertEquals(
            ConversationScheduleFailure.SESSION_MISSING,
            (cancelResult as ConversationScheduleResult.Failure).reason,
        )
        assertTrue("List must stay empty without an owner", coordinator.listScheduled("chat_1").isEmpty())
    }

    @Test
    fun `cancel succeeds even when cancelJob throws because the row is already gone`() {
        val backend = FakeBackend()
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "user_1" },
            backend = backend,
            now = { 10_000L },
        )

        val queueResult = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat_1",
                peerUserId = "peer_1",
                text = "Stale worker path",
                sendAtMillis = 60_000L,
                isGroup = false,
            )
        )
        val item = (queueResult as ConversationScheduleResult.Success).value
        backend.failCancelJob = RuntimeException("worker already dead")

        val cancelResult = coordinator.cancel(item.id)
        assertTrue(
            "Row is gone so a stale worker exits safely",
            cancelResult is ConversationScheduleResult.Success,
        )
        assertNull(backend.scheduled[item.id])
    }

    @Test
    fun `reschedule failure rolls back the row to previous text and time`() {
        val backend = FakeBackend()
        val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { "user_1" },
            backend = backend,
            now = { 10_000L },
        )

        val queueResult = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat_1",
                peerUserId = "peer_1",
                text = "v1",
                sendAtMillis = 60_000L,
                isGroup = false,
            )
        )
        val item = (queueResult as ConversationScheduleResult.Success).value
        backend.failRescheduleJob = RuntimeException("worker gone")

        val result = coordinator.reschedule(item.id, 90_000L, "v2")
        assertTrue(result is ConversationScheduleResult.Failure)
        assertEquals(
            ConversationScheduleFailure.STORAGE,
            (result as ConversationScheduleResult.Failure).reason,
        )
        val restored = backend.scheduled[item.id]
        assertNotNull(restored)
        assertEquals("v1", restored!!.text)
        assertEquals(60_000L, restored.sendAtMillis)
    }
}
