package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ScheduledMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledMessageFilterPolicyTest {

    private fun message(text: String) = ScheduledMessage(
        id = "1",
        chatId = "c1",
        peerUserId = "u1",
        text = text,
        sendAtMillis = 0L,
        createdAtMillis = 0L,
    )

    private val items = listOf(message("Buy milk"), message("Call mom"))

    @Test
    fun `matches text case-insensitively`() {
        assertTrue(ScheduledMessageFilterPolicy.matches(items[0], "MILK"))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(ScheduledMessageFilterPolicy.matches(items[0], "zzz"))
    }

    @Test
    fun `filter blank returns all`() {
        assertEquals(items, ScheduledMessageFilterPolicy.filter(items, "  "))
    }

    @Test
    fun `filter trims query`() {
        assertEquals(listOf(items[1]), ScheduledMessageFilterPolicy.filter(items, "  call "))
    }

    @Test
    fun `filter no match returns empty`() {
        assertEquals(
            emptyList<ScheduledMessage>(),
            ScheduledMessageFilterPolicy.filter(items, "zzz"),
        )
    }
}
