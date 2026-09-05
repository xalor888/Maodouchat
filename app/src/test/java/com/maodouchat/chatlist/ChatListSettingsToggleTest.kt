package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.network.ChatSettingsResponse
import com.maodouchat.ui.screen.chatlist.ChatSettingsToggle
import com.maodouchat.ui.screen.chatlist.applyConfirmedSettings
import com.maodouchat.ui.screen.chatlist.buildSettingsToggle
import com.maodouchat.ui.screen.chatlist.bumpOptimisticSettingsClock
import com.maodouchat.ui.screen.chatlist.selectUnreadBatchTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatListSettingsToggleTest {

    @Test
    fun pinOnSetsNowAndRequestsPinned() {
        val chat = Chat(id = "a", pinnedAt = 0, settingsUpdatedAt = 100)
        val m = buildSettingsToggle(chat, ChatSettingsToggle.PIN, nowMs = 1000)
        assertEquals(1000, m.optimistic.pinnedAt)
        assertEquals(true, m.request.pinned)
        assertEquals(1000, m.optimistic.settingsUpdatedAt)
    }

    @Test
    fun pinOffZeroesAndRequestsUnpinned() {
        val chat = Chat(id = "a", pinnedAt = 500, settingsUpdatedAt = 100)
        val m = buildSettingsToggle(chat, ChatSettingsToggle.PIN, nowMs = 1000)
        assertEquals(0, m.optimistic.pinnedAt)
        assertEquals(false, m.request.pinned)
    }

    @Test
    fun muteFlipsBothSides() {
        val chat = Chat(id = "a", notificationsMuted = false)
        val m = buildSettingsToggle(chat, ChatSettingsToggle.MUTE, nowMs = 1000)
        assertTrue(m.optimistic.notificationsMuted)
        assertEquals(true, m.request.notificationsMuted)
        assertNull(m.request.pinned)
    }

    @Test
    fun archiveAlsoUnpins() {
        val chat = Chat(id = "a", archived = false, pinnedAt = 500)
        val m = buildSettingsToggle(chat, ChatSettingsToggle.ARCHIVE, nowMs = 1000)
        assertTrue(m.optimistic.archived)
        assertEquals(0, m.optimistic.pinnedAt)
        assertEquals(true, m.request.archived)
        assertEquals(false, m.request.pinned)
    }

    @Test
    fun unarchiveKeepsPinUntouched() {
        val chat = Chat(id = "a", archived = true, pinnedAt = 500)
        val m = buildSettingsToggle(chat, ChatSettingsToggle.ARCHIVE, nowMs = 1000)
        assertFalse(m.optimistic.archived)
        assertEquals(500, m.optimistic.pinnedAt)
        assertEquals(false, m.request.archived)
        assertNull(m.request.pinned)
    }

    @Test
    fun markedUnreadFlips() {
        val chat = Chat(id = "a", markedUnread = false)
        val m = buildSettingsToggle(chat, ChatSettingsToggle.MARKED_UNREAD, nowMs = 1000)
        assertTrue(m.optimistic.markedUnread)
        assertEquals(true, m.request.markedUnread)
    }

    @Test
    fun confirmedMergeTakesServerSnapshot() {
        val optimistic = Chat(
            id = "a", pinnedAt = 111, notificationsMuted = true,
            archived = false, markedUnread = true, settingsUpdatedAt = 1000,
            lastMessage = "local-preview",
        )
        val confirmed = applyConfirmedSettings(
            optimistic,
            ChatSettingsResponse(
                chatId = "a", pinnedAt = 222, notificationsMuted = false,
                archived = true, markedUnread = false, updatedAt = 2000,
            ),
        )
        assertEquals(222, confirmed.pinnedAt)
        assertEquals(false, confirmed.notificationsMuted)
        assertEquals(true, confirmed.archived)
        assertEquals(false, confirmed.markedUnread)
        assertEquals(2000, confirmed.settingsUpdatedAt)
        // 非设置字段保持乐观值。
        assertEquals("local-preview", confirmed.lastMessage)
    }

    @Test
    fun clockIsMonotonic() {
        // 旧时钟更大时 +1 获胜，保证合并时乐观值不被旧快照覆盖。
        val chat = Chat(id = "a", settingsUpdatedAt = 5000)
        assertEquals(5001, bumpOptimisticSettingsClock(chat, nowMs = 1000).settingsUpdatedAt)
        assertEquals(1000, bumpOptimisticSettingsClock(chat.copy(settingsUpdatedAt = 10), nowMs = 1000).settingsUpdatedAt)
    }

    @Test
    fun markAllExcludesArchived() {
        val chats = listOf(
            Chat(id = "a", unreadCount = 2),
            Chat(id = "b", unreadCount = 2, archived = true),
        )
        val targets = selectUnreadBatchTargets(chats, setOf("a", "b"), excludeArchived = true)
        assertEquals(listOf("a"), targets.ordinary.map { it.id })
    }

    @Test
    fun batchDefaultKeepsArchived() {
        // 多选批量已读不过滤归档（与旧实现一致）。
        val chats = listOf(Chat(id = "b", unreadCount = 2, archived = true))
        val targets = selectUnreadBatchTargets(chats, setOf("b"))
        assertEquals(listOf("b"), targets.ordinary.map { it.id })
    }
}
