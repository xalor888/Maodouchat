package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatScheduleController
import com.maodouchat.core.model.ConversationScheduleBackend
import com.maodouchat.core.model.ConversationScheduleCoordinator
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessageRequest
import com.maodouchat.core.model.SessionSnapshot
import com.maodouchat.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduledMessageControllerTest {

    private class FakeScheduleBackend : ConversationScheduleBackend {
        val items = mutableMapOf<String, ScheduledMessage>()
        val scheduledJobs = mutableListOf<String>()
        val cancelledJobs = mutableListOf<String>()
        val rescheduledJobs = mutableListOf<String>()
        private var seq = 0

        override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> =
            items.values.filter { it.ownerUserId == ownerUserId }

        override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> =
            items.values.filter { it.ownerUserId == ownerUserId && it.chatId == chatId }

        override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? =
            items[id]?.takeIf { it.ownerUserId == ownerUserId }

        override fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage? {
            val item = ScheduledMessage(
                id = "sm-${++seq}",
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
            items[item.id] = item
            return item
        }

        override fun updateScheduled(
            ownerUserId: String,
            id: String,
            text: String?,
            sendAtMillis: Long?,
        ): ScheduledMessage? {
            val prev = getScheduled(ownerUserId, id) ?: return null
            val updated = prev.copy(text = text ?: prev.text, sendAtMillis = sendAtMillis ?: prev.sendAtMillis)
            items[id] = updated
            return updated
        }

        override fun removeScheduled(ownerUserId: String, id: String): Boolean {
            getScheduled(ownerUserId, id) ?: return false
            items.remove(id)
            return true
        }

        override fun clearScheduled(ownerUserId: String, chatId: String): List<String> {
            val ids = listScheduled(ownerUserId, chatId).map { it.id }
            ids.forEach { items.remove(it) }
            return ids
        }

        override fun scheduleJob(item: ScheduledMessage) {
            scheduledJobs += item.id
        }

        override fun cancelJob(id: String) {
            cancelledJobs += id
        }

        override fun rescheduleJob(item: ScheduledMessage) {
            rescheduledJobs += item.id
        }

        override fun listReminders(ownerUserId: String): List<MessageReminder> = emptyList()
        override fun upsertReminder(reminder: MessageReminder) {}
        override fun removeReminder(ownerUserId: String, id: String) {}
        override fun clearReminders(ownerUserId: String, chatId: String) {}
        override fun scheduleReminderJob(reminder: MessageReminder) {}
        override fun cancelReminderJob(id: String) {}
    }

    private class Harness(
        var ownerId: String = "u1",
        var activeChatId: String = "c1",
        var chatId: String = "c1",
        var enabled: Boolean = true,
        var blocked: Boolean = false,
    ) {
        val uiState = MutableStateFlow(
            ChatDetailUiState(contact = User("peer1", "Peer"), inputText = "hello"),
        )
        val backend = FakeScheduleBackend()
        private val coordinator = ConversationScheduleCoordinator(
            ownerUserId = { ownerId },
            backend = backend,
            now = { 1000L },
        )
        val sent = mutableListOf<String?>()
        var acceptSend = true
        var commit: (() -> Unit)? = null
        var failure: (() -> Unit)? = null
        val controller = ScheduledMessageController(
            chatScheduleController = ChatScheduleController(coordinator, scheduledMessagesEnabled = { enabled }),
            uiState = uiState,
            textProvider = { message, args ->
                "[${message.name}]" + if (args.isEmpty()) "" else " " + args.joinToString(",")
            },
            session = { SessionSnapshot(token = "t", userId = ownerId.takeIf { it.isNotBlank() }) },
            activeChatId = { activeChatId },
            chatId = chatId,
            clearDraft = {},
            sendMessage = { forceText, onDurableCommit, onDurableFailure ->
                sent += forceText
                commit = onDurableCommit
                failure = onDurableFailure
                acceptSend
            },
        )

        fun queueAt(sendAt: Long = 9_000_000_000_000L): String? {
            // 成功排队会清空输入框，连续排队前先补回输入，否则第二次是空文本、按 INVALID_REQUEST 静默拒绝。
            uiState.update { it.copy(inputText = "hello") }
            controller.scheduleMessageAt(sendAt)
            return uiState.value.scheduledMessages.firstOrNull()?.id
        }
    }

    @Test
    fun `scheduleMessageAt success clears input and shows queued info`() {
        val h = Harness()
        val id = h.queueAt()
        assertTrue(id != null)
        assertEquals("", h.uiState.value.inputText)
        assertEquals(1, h.uiState.value.scheduledMessages.size)
        assertTrue(h.uiState.value.scheduledInfoMessage?.startsWith("[QUEUED_AT]") == true)
        assertEquals(listOf(id), h.backend.scheduledJobs)
    }

    @Test
    fun `scheduleMessageAt with blank text leaves state untouched`() {
        val h = Harness()
        h.uiState.value = h.uiState.value.copy(inputText = "   ")
        h.controller.scheduleMessageAt(9_000_000_000_000L)
        assertEquals(emptyList<ScheduledMessage>(), h.uiState.value.scheduledMessages)
        assertNull(h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `scheduleMessageAt without session shows expired`() {
        val h = Harness(ownerId = "")
        h.controller.scheduleMessageAt(9_000_000_000_000L)
        assertEquals("[SESSION_EXPIRED]", h.uiState.value.scheduledInfoMessage)
        assertTrue(h.uiState.value.scheduledMessages.isEmpty())
    }

    @Test
    fun `scheduleMessageAt when blocked shows blocked with display name`() {
        val h = Harness(blocked = true)
        h.uiState.value = h.uiState.value.copy(isContactBlocked = true)
        h.controller.scheduleMessageAt(9_000_000_000_000L)
        assertEquals("[BLOCKED] Peer", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `scheduleMessageAt when disabled shows group warning`() {
        val h = Harness(enabled = false)
        h.controller.scheduleMessageAt(9_000_000_000_000L)
        assertEquals("[DISABLED]", h.uiState.value.groupEncryptionWarning)
        assertNull(h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `scheduleMessageRepeat with non-positive interval is a no-op`() {
        val h = Harness()
        h.controller.scheduleMessageRepeat(0L)
        assertTrue(h.uiState.value.scheduledMessages.isEmpty())
        assertNull(h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `scheduleMessageRepeat queues with repeat interval`() {
        val h = Harness()
        h.controller.scheduleMessageRepeat(3_600_000L)
        val item = h.uiState.value.scheduledMessages.single()
        assertEquals(3_600_000L, item.repeatIntervalMs)
        assertTrue(h.uiState.value.scheduledInfoMessage?.startsWith("[QUEUED_AT]") == true)
    }

    @Test
    fun `refreshScheduledMessages reloads list`() {
        val h = Harness()
        val id = h.queueAt()
        h.uiState.value = h.uiState.value.copy(scheduledMessages = emptyList())
        h.controller.refreshScheduledMessages()
        assertEquals(listOf(id), h.uiState.value.scheduledMessages.map { it.id })
    }

    @Test
    fun `cancelScheduledMessage success clears list and shows cancelled`() {
        val h = Harness()
        val id = h.queueAt()
        h.controller.cancelScheduledMessage(requireNotNull(id))
        assertTrue(h.uiState.value.scheduledMessages.isEmpty())
        assertEquals("[CANCELLED]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `cancelScheduledMessage unknown id shows failed`() {
        val h = Harness()
        h.controller.cancelScheduledMessage("nope")
        assertEquals("[FAILED]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `sendScheduledNow accepted then committed removes item and shows sent`() {
        val h = Harness()
        val id = h.queueAt()
        h.controller.sendScheduledNow(requireNotNull(id))
        assertEquals(listOf("hello"), h.sent)
        requireNotNull(h.commit)()
        assertTrue(h.uiState.value.scheduledMessages.isEmpty())
        assertEquals("[SENT_NOW]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `sendScheduledNow rejected send restores item and shows failed`() {
        val h = Harness()
        val id = h.queueAt()
        h.acceptSend = false
        h.controller.sendScheduledNow(requireNotNull(id))
        assertEquals(1, h.uiState.value.scheduledMessages.size)
        assertEquals("[FAILED]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `sendScheduledNow failure callback restores item and keeps queued info`() {
        val h = Harness()
        val id = h.queueAt()
        h.controller.sendScheduledNow(requireNotNull(id))
        requireNotNull(h.failure)()
        assertEquals(1, h.uiState.value.scheduledMessages.size)
        assertTrue(h.uiState.value.scheduledInfoMessage?.startsWith("[QUEUED_AT]") == true)
    }

    @Test
    fun `sendScheduledNow unknown id shows failed`() {
        val h = Harness()
        h.controller.sendScheduledNow("nope")
        assertEquals("[FAILED]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `cancelAllScheduledMessages clears all and shows cancelled all`() {
        val h = Harness()
        h.queueAt()
        h.queueAt()
        assertEquals(2, h.uiState.value.scheduledMessages.size)
        h.controller.cancelAllScheduledMessages()
        assertTrue(h.uiState.value.scheduledMessages.isEmpty())
        assertEquals("[CANCELLED_ALL]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `cancelAllScheduledMessages with blank chat is a no-op`() {
        val h = Harness(activeChatId = "", chatId = "")
        h.controller.cancelAllScheduledMessages()
        assertNull(h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `rescheduleScheduledMessageAt updates time and shows rescheduled`() {
        val h = Harness()
        val id = h.queueAt()
        h.controller.rescheduleScheduledMessageAt(requireNotNull(id), 9_100_000_000_000L)
        assertEquals(9_100_000_000_000L, h.uiState.value.scheduledMessages.single().sendAtMillis)
        assertTrue(h.uiState.value.scheduledInfoMessage?.startsWith("[RESCHEDULED]") == true)
    }

    @Test
    fun `rescheduleScheduledMessage unknown id shows failed`() {
        val h = Harness()
        h.controller.rescheduleScheduledMessage("nope", 3_600_000L)
        assertEquals("[FAILED]", h.uiState.value.scheduledInfoMessage)
    }

    @Test
    fun `clearScheduledInfo clears info`() {
        val h = Harness()
        h.queueAt()
        h.controller.clearScheduledInfo()
        assertNull(h.uiState.value.scheduledInfoMessage)
    }
}
