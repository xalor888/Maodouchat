package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterFilterPolicyTest {

    private fun item(
        id: String,
        title: String,
        type: String = "MESSAGE",
        subtitle: String? = null,
        preview: String? = null,
        extra: Map<String, String> = emptyMap()
    ) = NotificationCenterItem(
        id = id, type = type, mergeKey = "m-$id",
        title = title, subtitle = subtitle, preview = preview, extra = extra
    )

    private val items = listOf(
        item("1", "Alice 发来消息", subtitle = "你好"),
        item("2", "未接来电", type = "MISSED_CALL", preview = "来自 Bob"),
        item("3", "群邀请", type = "GROUP_INVITE", extra = mapOf("group" to "跑团小队")),
    )

    @Test
    fun `blank query returns all`() {
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, ""))
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, "  "))
    }

    @Test
    fun `matches by title case-insensitively`() {
        assertEquals(listOf(items[0]), NotificationCenterFilterPolicy.filter(items, "alice"))
    }

    @Test
    fun `matches by subtitle and preview`() {
        assertEquals(listOf(items[0]), NotificationCenterFilterPolicy.filter(items, "你好"))
        assertEquals(listOf(items[1]), NotificationCenterFilterPolicy.filter(items, "bob"))
    }

    @Test
    fun `matches by type`() {
        assertEquals(listOf(items[2]), NotificationCenterFilterPolicy.filter(items, "GROUP_INVITE"))
    }

    @Test
    fun `matches by extra values only`() {
        assertEquals(listOf(items[2]), NotificationCenterFilterPolicy.filter(items, "跑团"))
        assertFalse(NotificationCenterFilterPolicy.matches(items[2], "m-3"))
    }

    @Test
    fun `no match returns empty and query is trimmed`() {
        assertEquals(emptyList<NotificationCenterItem>(), NotificationCenterFilterPolicy.filter(items, "zzz"))
        assertEquals(listOf(items[1]), NotificationCenterFilterPolicy.filter(items, "  未接 "))
    }
}
