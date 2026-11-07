package com.maodouchat.core.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

class MessagingV2ReactionScheduleModelsTest {

    @Test
    fun reactionAddsForNewActor() {
        val existing = listOf(MessageReaction(userId = "alice", emoji = "like", reactedAt = 10L))
        val updated = ReactionMutationPolicy.apply(existing, "bob", "laugh", 20L)
        assertEquals(2, updated.size)
        assertEquals("laugh", updated.last().emoji)
    }

    @Test
    fun reactionReplacesActorAndNullEmojiRemoves() {
        val existing = listOf(
            MessageReaction(userId = "alice", emoji = "like", reactedAt = 10L),
            MessageReaction(userId = "bob", emoji = "wow", reactedAt = 11L),
        )
        val replaced = ReactionMutationPolicy.apply(existing, "alice", "love", 20L)
        assertEquals(2, replaced.size)
        assertEquals("love", replaced.first { it.userId == "alice" }.emoji)
        val removed = ReactionMutationPolicy.apply(replaced, "alice", null, 30L)
        assertEquals(listOf("bob"), removed.map { it.userId })
    }

    @Test
    fun eventOutboxFakeRecordsEnqueue() = runBlocking {
        val recorded = mutableListOf<Triple<String, MessagingV2Event, Long?>>()
        val outbox = MessagingV2EventOutbox { conversationId, event, groupRevision ->
            recorded += Triple(conversationId, event, groupRevision)
        }
        val event = MessagingV2Event(action = "EDIT", targetMessageId = "m1")
        outbox.enqueue("chat-1", event, 7L)
        assertEquals(listOf(Triple("chat-1", event, 7L)), recorded)
    }

    @Test
    fun scheduleCoordinatorRejectsBlankOwner() {
        val coordinator = scheduleCoordinator(owner = "   ")
        assertEquals(emptyList<ScheduledMessage>(), coordinator.listScheduled("chat-1"))
        val result = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat-1", peerUserId = "u2", text = "hi",
                sendAtMillis = 1000L, isGroup = false,
            ),
        )
        assertIs<ConversationScheduleResult.Failure>(result)
        assertEquals(ConversationScheduleFailure.SESSION_MISSING, result.reason)
    }

    @Test
    fun scheduleCoordinatorRejectsBlankChatId() {
        val coordinator = scheduleCoordinator()
        val result = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "  ", peerUserId = "u2", text = "hi",
                sendAtMillis = 1000L, isGroup = false,
            ),
        )
        assertIs<ConversationScheduleResult.Failure>(result)
        assertEquals(ConversationScheduleFailure.INVALID_REQUEST, result.reason)
    }

    @Test
    fun scheduleCoordinatorQueuesAndLists() {
        val backend = FakeScheduleBackend()
        val coordinator = scheduleCoordinator(backend = backend)
        val result = coordinator.queue(
            ScheduledMessageRequest(
                chatId = "chat-1", peerUserId = "u2", text = "hello",
                sendAtMillis = 5000L, isGroup = false,
            ),
        )
        assertIs<ConversationScheduleResult.Success<ScheduledMessage>>(result)
        assertEquals("hello", result.value.text)
        assertEquals(1, coordinator.listScheduled("chat-1").size)
        assertTrue(backend.jobs.contains("sched-1"))
    }

    private fun scheduleCoordinator(
        owner: String = "u1",
        backend: ConversationScheduleBackend = FakeScheduleBackend(),
    ) = ConversationScheduleCoordinator(
        ownerUserId = { owner },
        backend = backend,
        now = { 1000L },
    )

    private class FakeScheduleBackend : ConversationScheduleBackend {
        private val items = mutableListOf<ScheduledMessage>()
        val jobs = mutableSetOf<String>()

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

        override fun listReminders(ownerUserId: String) = emptyList<MessageReminder>()

        override fun upsertReminder(reminder: MessageReminder) = Unit

        override fun removeReminder(ownerUserId: String, id: String) = Unit

        override fun clearReminders(ownerUserId: String, chatId: String) = Unit

        override fun scheduleReminderJob(reminder: MessageReminder) = Unit

        override fun cancelReminderJob(id: String) = Unit
    }
}
