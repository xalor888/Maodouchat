package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ScheduledMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledMessageBannerPolicyTest {

    private fun item(id: String) = ScheduledMessage(
        id = id, chatId = "c1", peerUserId = "u1", text = "hi",
        sendAtMillis = 1L, createdAtMillis = 0L
    )

    @Test
    fun `preview keeps first three`() {
        val items = (1..5).map { item("m$it") }
        assertEquals(items.take(3), ScheduledMessageBannerPolicy.preview(items))
    }

    @Test
    fun `preview passes through short lists`() {
        assertEquals(emptyList(), ScheduledMessageBannerPolicy.preview(emptyList()))
        val two = listOf(item("m1"), item("m2"))
        assertEquals(two, ScheduledMessageBannerPolicy.preview(two))
    }

    @Test
    fun `has more only above three`() {
        assertFalse(ScheduledMessageBannerPolicy.hasMore(3))
        assertTrue(ScheduledMessageBannerPolicy.hasMore(4))
    }

    @Test
    fun `search field appears at four`() {
        assertFalse(ScheduledMessageBannerPolicy.showSearchField(3))
        assertTrue(ScheduledMessageBannerPolicy.showSearchField(4))
    }

    @Test
    fun `search query clips at 120`() {
        assertEquals("a".repeat(120), ScheduledMessageBannerPolicy.clipSearchQuery("a".repeat(121)))
        assertEquals("abc", ScheduledMessageBannerPolicy.clipSearchQuery("abc"))
    }
}
