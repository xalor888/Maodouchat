package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWidgetModelsTest {

    @Test
    fun `widget config defaults show unread badge`() {
        val config = WidgetConfig()
        assertTrue(config.chatIds.isEmpty())
        assertTrue(config.showUnreadBadge)
        assertEquals(false, config.compact)
    }

    @Test
    fun `widget snapshot carries rows and totals`() {
        val row = WidgetRow("c1", "群聊", "你好", "10:00", 3)
        val snapshot = WidgetSnapshot(listOf(row), 3, "u1", false, 1000L)
        assertEquals(1, snapshot.rows.size)
        assertEquals(3, snapshot.totalUnread)
        assertEquals("c1", snapshot.rows.first().chatId)
    }
}
