package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationCenterFoldPolicyTest {

    private fun item(
        id: String,
        type: String = "MESSAGE",
        mergeKey: String = "msg_c1",
        title: String = "t",
        preview: String? = "p",
        read: Boolean = false,
        count: Int = 1,
        extra: Map<String, String> = emptyMap(),
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L,
    ): NotificationCenterItem = NotificationCenterItem(
        id = id,
        type = type,
        mergeKey = mergeKey,
        title = title,
        subtitle = null,
        preview = preview,
        deeplink = null,
        extra = extra,
        read = read,
        count = count,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Test
    fun foldIntoEmptyListPrepends() {
        val result = NotificationCenterFoldPolicy.foldInto(emptyList(), item("m1"))
        assertEquals(1, result.size)
        assertEquals("m1", result[0].id)
        assertEquals(1, result[0].count)
    }

    @Test
    fun foldIntoMergesSameKeyUnread() {
        val current = listOf(item("old", count = 2, updatedAt = 1000L))
        val incoming = item("new", title = "new title", preview = "new preview", updatedAt = 2000L)
        val result = NotificationCenterFoldPolicy.foldInto(current, incoming)
        assertEquals(1, result.size)
        assertEquals(3, result[0].count)
        assertEquals("new title", result[0].title)
        assertEquals("new preview", result[0].preview)
        assertEquals(2000L, result[0].updatedAt)
        assertEquals("new", result[0].id)
    }

    @Test
    fun foldIntoSameMessageDoesNotIncrement() {
        val current = listOf(
            item("msg_c1_m1", count = 1, extra = mapOf("messageId" to "m1"))
        )
        val incoming = item(
            "msg_c1_m1", extra = mapOf("messageId" to "m1"), updatedAt = 2000L
        )
        val result = NotificationCenterFoldPolicy.foldInto(current, incoming)
        assertEquals(1, result.size)
        assertEquals(1, result[0].count)
        assertEquals("msg_c1_m1", result[0].id)
    }

    @Test
    fun foldIntoSameMissedCallDoesNotIncrement() {
        val current = listOf(
            item(
                "missed_call1", type = "MISSED_CALL", mergeKey = "missed_peer",
                extra = mapOf("callId" to "call1")
            )
        )
        val incoming = item(
            "missed_call1", type = "MISSED_CALL", mergeKey = "missed_peer",
            extra = mapOf("callId" to "call1"), updatedAt = 2000L
        )
        val result = NotificationCenterFoldPolicy.foldInto(current, incoming)
        assertEquals(1, result.size)
        assertEquals(1, result[0].count)
    }

    @Test
    fun foldIntoReadRowDoesNotMerge() {
        val current = listOf(item("old", read = true))
        val result = NotificationCenterFoldPolicy.foldInto(current, item("new"))
        assertEquals(2, result.size)
        assertEquals("new", result[0].id)
        assertEquals("old", result[1].id)
    }

    @Test
    fun foldIntoDifferentMergeKeyDoesNotMerge() {
        val current = listOf(item("old", mergeKey = "msg_c1"))
        val result = NotificationCenterFoldPolicy.foldInto(
            current, item("new", mergeKey = "msg_c2")
        )
        assertEquals(2, result.size)
        assertEquals("new", result[0].id)
    }

    @Test
    fun foldIntoDifferentTypeDoesNotMerge() {
        val current = listOf(item("old", type = "MESSAGE"))
        val result = NotificationCenterFoldPolicy.foldInto(
            current, item("new", type = "MISSED_CALL")
        )
        assertEquals(2, result.size)
    }

    @Test
    fun foldIntoTruncatesAtMaxKeep() {
        assertEquals(520, NotificationCenterFoldPolicy.MAX_KEEP)
        val current = (0 until 3).map { item("old$it", mergeKey = "msg_c$it") }
        val result = NotificationCenterFoldPolicy.foldInto(
            current, item("new", mergeKey = "msg_new"), maxKeep = 3
        )
        assertEquals(3, result.size)
        assertEquals("new", result[0].id)
        assertEquals("old1", result[2].id)
        assertFalse(result.any { it.id == "old2" })
    }

    @Test
    fun markAllReadMarksUnreadOnly() {
        val readRow = item("r1", read = true, updatedAt = 1000L)
        val unreadRow = item("r2", updatedAt = 1000L)
        val result = NotificationCenterFoldPolicy.markAllRead(listOf(readRow, unreadRow), 2000L)
        assertTrue(result[0].read)
        assertEquals(1000L, result[0].updatedAt)
        assertTrue(result[1].read)
        assertEquals(2000L, result[1].updatedAt)
    }

    @Test
    fun markReadMarksOnlyTarget() {
        val rows = listOf(item("a"), item("b"))
        val result = NotificationCenterFoldPolicy.markRead(rows, "b", 2000L)
        assertFalse(result[0].read)
        assertTrue(result[1].read)
        assertEquals(2000L, result[1].updatedAt)
    }

    @Test
    fun markUnreadMarksOnlyTarget() {
        val rows = listOf(item("a", read = true), item("b", read = true))
        val result = NotificationCenterFoldPolicy.markUnread(rows, "a", 2000L)
        assertFalse(result[0].read)
        assertEquals(2000L, result[0].updatedAt)
        assertTrue(result[1].read)
    }

    @Test
    fun markChatMessagesReadOnlyMessageRows() {
        val rows = listOf(
            item("m1", mergeKey = "msg_c1"),
            item("t1", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item("m2", mergeKey = "msg_c2"),
        )
        val result = NotificationCenterFoldPolicy.markChatMessagesRead(rows, "c1", 2000L)
        assertTrue(result[0].read)
        assertFalse(result[1].read)
        assertFalse(result[2].read)
    }

    @Test
    fun markAiTasksReadOnlyAiTaskRows() {
        val rows = listOf(
            item("t1", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item("m1", mergeKey = "msg_c1"),
        )
        val result = NotificationCenterFoldPolicy.markAiTasksRead(rows, "c1", 2000L)
        assertTrue(result[0].read)
        assertFalse(result[1].read)
    }

    @Test
    fun markPostInteractionsReadOnlyPostRows() {
        val rows = listOf(
            item("p1", type = "POST_INTERACTION", mergeKey = "post_9",
                extra = mapOf("postId" to "9")),
            item("p2", type = "POST_INTERACTION", mergeKey = "post_8",
                extra = mapOf("postId" to "8")),
        )
        val result = NotificationCenterFoldPolicy.markPostInteractionsRead(rows, "9", 2000L)
        assertTrue(result[0].read)
        assertFalse(result[1].read)
    }

    @Test
    fun removeChatItemsDropsChatRows() {
        val rows = listOf(
            item("m1", mergeKey = "msg_c1"),
            item("t1", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item("m2", mergeKey = "msg_c2"),
        )
        val result = NotificationCenterFoldPolicy.removeChatItems(rows, "c1")
        assertEquals(listOf("m2"), result.map { it.id })
    }

    @Test
    fun removeMessageReferencesDropsByExtraMessageId() {
        val rows = listOf(
            item("msg_c1_m1", extra = mapOf("messageId" to "m1")),
            item("msg_c1_m2", extra = mapOf("messageId" to "m2")),
        )
        val result = NotificationCenterFoldPolicy.removeMessageReferences(rows, "m1")
        assertEquals(listOf("msg_c1_m2"), result.map { it.id })
    }

    @Test
    fun deleteItemsForMessagesDropsMultiple() {
        val rows = listOf(
            item("msg_c1_m1", extra = mapOf("messageId" to "m1")),
            item("msg_c1_m2", extra = mapOf("messageId" to "m2")),
            item("msg_c1_m3", extra = mapOf("messageId" to "m3")),
        )
        val result = NotificationCenterFoldPolicy.deleteItemsForMessages(
            rows, listOf("m1", "m1", " ", "m3")
        )
        assertEquals(listOf("msg_c1_m2"), result.map { it.id })
    }

    @Test
    fun updateMessagePreviewUpdatesOnlyMatchingRow() {
        val rows = listOf(
            item("msg_c1_m1", preview = "old", extra = mapOf("messageId" to "m1"),
                updatedAt = 1000L),
            item("msg_c1_m2", preview = "keep", extra = mapOf("messageId" to "m2"),
                updatedAt = 1000L),
        )
        val result = NotificationCenterFoldPolicy.updateMessagePreview(
            rows, "m1", "edited", 2000L
        )
        assertEquals("edited", result[0].preview)
        assertEquals(2000L, result[0].updatedAt)
        assertEquals("keep", result[1].preview)
        assertEquals(1000L, result[1].updatedAt)
        val same = NotificationCenterFoldPolicy.updateMessagePreview(rows, "m1", "old", 2000L)
        assertEquals(1000L, same[0].updatedAt)
    }
}
