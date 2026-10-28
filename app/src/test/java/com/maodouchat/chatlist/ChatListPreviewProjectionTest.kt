package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatlist.applyPreviewToChats
import com.maodouchat.ui.screen.chatlist.restoreChatSorted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatListPreviewProjectionTest {

    private fun chat(
        id: String,
        pinnedAt: Long = 0,
        lastTime: Long = 100,
        unread: Int = 0,
    ) = Chat(id = id, pinnedAt = pinnedAt, lastMessageTime = lastTime, unreadCount = unread)

    @Test
    fun missingChatIsNoop() {
        val chats = listOf(chat("a"))
        assertTrue(applyPreviewToChats(chats, "ghost", "hi", MessageType.TEXT, 200) == chats)
    }

    @Test
    fun lateEventDoesNotRewindSortKey() {
        val chats = listOf(chat("a", lastTime = 500))
        val next = applyPreviewToChats(chats, "a", "late", MessageType.TEXT, 100)
        assertEquals(500, next.single().lastMessageTime)
        assertEquals("late", next.single().lastMessage)
    }

    @Test
    fun forceTimestampRewinds() {
        val chats = listOf(chat("a", lastTime = 500))
        val next = applyPreviewToChats(
            chats, "a", "", MessageType.TEXT, 100, unreadDelta = 0, forceTimestamp = true
        )
        assertEquals(100, next.single().lastMessageTime)
    }

    @Test
    fun unreadDeltaClampedAtZero() {
        val chats = listOf(chat("a", unread = 1))
        val next = applyPreviewToChats(chats, "a", "x", MessageType.TEXT, 200, unreadDelta = -5)
        assertEquals(0, next.single().unreadCount)
    }

    @Test
    fun unpinnedChatJumpsBeforeUnpinnedOnly() {
        val chats = listOf(
            chat("pinned", pinnedAt = 50, lastTime = 10),
            chat("old", lastTime = 300),
            chat("fresh", lastTime = 100),
        )
        val next = applyPreviewToChats(chats, "fresh", "new", MessageType.TEXT, 400)
        assertEquals(listOf("pinned", "fresh", "old"), next.map { it.id })
    }

    @Test
    fun pinnedChatsSortByPinnedAt() {
        val chats = listOf(chat("p1", pinnedAt = 10), chat("p2", pinnedAt = 90))
        val next = applyPreviewToChats(chats, "p1", "x", MessageType.TEXT, 50)
        assertEquals(listOf("p2", "p1"), next.map { it.id })
    }

    @Test
    fun previewUpdatesTypeAndAccumulatesUnread() {
        val chats = listOf(chat("a", unread = 2))
        val next = applyPreviewToChats(chats, "a", "pic", MessageType.IMAGE, 200, unreadDelta = 3)
        assertEquals("pic", next.single().lastMessage)
        assertEquals(MessageType.IMAGE, next.single().lastMessageType)
        assertEquals(5, next.single().unreadCount)
    }

    @Test
    fun previewWithEqualTimestampKeepsTimeButUpdatesText() {
        val chats = listOf(chat("a", lastTime = 500))
        val next = applyPreviewToChats(chats, "a", "same", MessageType.TEXT, 500)
        assertEquals(500, next.single().lastMessageTime)
        assertEquals("same", next.single().lastMessage)
    }

    @Test
    fun pinnedTargetDoesNotYieldToUnpinnedOnNewerTime() {
        val chats = listOf(
            chat("pinned", pinnedAt = 50, lastTime = 10),
            chat("plain", lastTime = 300),
        )
        val next = applyPreviewToChats(chats, "pinned", "new", MessageType.TEXT, 400)
        assertEquals(listOf("pinned", "plain"), next.map { it.id })
        assertEquals(400, next.first().lastMessageTime)
    }

    @Test
    fun restoreInsertsUnpinnedByRecencyAfterPinned() {
        val existing = listOf(chat("pinned", pinnedAt = 10, lastTime = 1), chat("old", lastTime = 300))
        val next = restoreChatSorted(existing, chat("back", lastTime = 400))
        assertEquals(listOf("pinned", "back", "old"), next.map { it.id })
    }

    @Test
    fun restoreKeepsStableOrderOnEqualSortKeys() {
        val existing = listOf(chat("a", lastTime = 100), chat("b", lastTime = 100))
        val next = restoreChatSorted(existing, chat("c", lastTime = 100))
        assertEquals(listOf("a", "b", "c"), next.map { it.id })
    }

    @Test
    fun restoreInsertsByPinnedFirstOrder() {
        val existing = listOf(chat("pinned", pinnedAt = 10, lastTime = 1), chat("old", lastTime = 300))
        val next = restoreChatSorted(existing, chat("back", lastTime = 200))
        assertEquals(listOf("pinned", "old", "back"), next.map { it.id })
        val nextPinned = restoreChatSorted(existing, chat("back", pinnedAt = 99, lastTime = 1))
        assertEquals(listOf("back", "pinned", "old"), nextPinned.map { it.id })
    }
}
