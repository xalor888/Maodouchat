package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.UpdateChatSettingsRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ChatListSettingsToggleCoordinator` 随迁入 `:domain:conversation` 时新增的契约测试。 */
class ChatListSettingsToggleCoordinatorTest {

    private class Harness {
        var chats = listOf(
            Chat(id = "a", pinnedAt = 0, notificationsMuted = false, archived = false, markedUnread = false),
            Chat(id = "b", pinnedAt = 1000, notificationsMuted = true, archived = false, markedUnread = true),
        )
        var errors = mutableListOf<String>()
        var pinEnabled = true
        var muteEnabled = true
        var archiveEnabled = true
        var markedUnreadEnabled = true
        var lastUpdate: Triple<Chat, Chat, UpdateChatSettingsRequest>? = null
        val coordinator = ChatListSettingsToggleCoordinator(
            getChats = { chats },
            showError = { errors += it },
            pinEnabled = { pinEnabled },
            muteEnabled = { muteEnabled },
            archiveEnabled = { archiveEnabled },
            markedUnreadEnabled = { markedUnreadEnabled },
            featureDisabledText = { "disabled" },
            updateChatSettings = { chat, optimistic, request -> lastUpdate = Triple(chat, optimistic, request) },
        )
    }

    @Test
    fun togglePinnedPinsUnpinnedChat() {
        val h = Harness()
        h.coordinator.togglePinned("a")
        val (chat, optimistic, request) = h.lastUpdate!!
        assertEquals("a", chat.id)
        assertTrue(optimistic.pinnedAt > 0)
        assertEquals(true, request.pinned)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun togglePinnedUnpinsPinnedChat() {
        val h = Harness()
        h.coordinator.togglePinned("b")
        val (_, optimistic, request) = h.lastUpdate!!
        assertEquals(0, optimistic.pinnedAt)
        assertEquals(false, request.pinned)
    }

    @Test
    fun toggleNotificationsMutedFlipsMute() {
        val h = Harness()
        h.coordinator.toggleNotificationsMuted("a")
        val (_, optimistic, request) = h.lastUpdate!!
        assertTrue(optimistic.notificationsMuted)
        assertEquals(true, request.notificationsMuted)
    }

    @Test
    fun toggleArchivedArchivesAndClearsPin() {
        val h = Harness()
        h.coordinator.toggleArchived("b")
        val (_, optimistic, request) = h.lastUpdate!!
        assertTrue(optimistic.archived)
        assertEquals(0, optimistic.pinnedAt)
        assertEquals(true, request.archived)
        assertEquals(false, request.pinned)
    }

    @Test
    fun toggleMarkedUnreadFlipsFlag() {
        val h = Harness()
        h.coordinator.toggleMarkedUnread("b")
        val (_, optimistic, request) = h.lastUpdate!!
        assertEquals(false, optimistic.markedUnread)
        assertEquals(false, request.markedUnread)
    }

    @Test
    fun disabledFlagShowsErrorAndSkipsUpdate() {
        val h = Harness()
        h.muteEnabled = false
        h.coordinator.toggleNotificationsMuted("a")
        assertEquals(listOf("disabled"), h.errors)
        assertNull(h.lastUpdate)
        // 其余开关不受影响
        h.coordinator.togglePinned("a")
        assertEquals("a", h.lastUpdate!!.first.id)
    }

    @Test
    fun unknownChatIdIsNoop() {
        val h = Harness()
        h.coordinator.togglePinned("missing")
        assertNull(h.lastUpdate)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun toggleReadsLatestChatsSnapshot() {
        val h = Harness()
        // 调用方传入的旧快照不可信：现查 getChats，b 已被外部取消置顶
        h.chats = listOf(Chat(id = "b", pinnedAt = 0))
        h.coordinator.togglePinned("b")
        val (_, optimistic, request) = h.lastUpdate!!
        assertTrue(optimistic.pinnedAt > 0)
        assertEquals(true, request.pinned)
    }
}
