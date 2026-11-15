package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReminder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageReminderRemovePolicyTest {

    private fun reminder(id: String) =
        MessageReminder(
            id = id,
            chatId = "c1",
            messageId = "m1",
            messagePreview = "hi",
            remindAtMillis = 1000L,
            createdAtMillis = 500L,
            ownerUserId = "u1"
        )

    @Test
    fun `removes matching reminder and keeps order and others`() {
        val reminders = listOf(reminder("r1"), reminder("r2"), reminder("r3"))
        val result = MessageReminderRemovePolicy.removeById(reminders, "r2")
        assertEquals(listOf(reminder("r1"), reminder("r3")), result)
    }

    @Test
    fun `removes first reminder`() {
        val reminders = listOf(reminder("r1"), reminder("r2"))
        val result = MessageReminderRemovePolicy.removeById(reminders, "r1")
        assertEquals(listOf(reminder("r2")), result)
    }

    @Test
    fun `removes last reminder`() {
        val reminders = listOf(reminder("r1"), reminder("r2"))
        val result = MessageReminderRemovePolicy.removeById(reminders, "r2")
        assertEquals(listOf(reminder("r1")), result)
    }

    @Test
    fun `unknown id returns list unchanged`() {
        val reminders = listOf(reminder("r1"), reminder("r2"))
        val result = MessageReminderRemovePolicy.removeById(reminders, "r9")
        assertEquals(reminders, result)
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(
            emptyList<MessageReminder>(),
            MessageReminderRemovePolicy.removeById(emptyList<MessageReminder>(), "r1")
        )
    }
}
