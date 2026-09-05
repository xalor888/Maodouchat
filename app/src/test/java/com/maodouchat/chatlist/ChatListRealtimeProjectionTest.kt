package com.maodouchat.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.ui.screen.chatlist.applyPresenceProjection
import com.maodouchat.ui.screen.chatlist.buildAdminBroadcastProjection
import com.maodouchat.ui.screen.chatlist.zeroChatUnread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatListRealtimeProjectionTest {

    private fun chat(id: String, vararg users: User, unread: Int = 0, marked: Boolean = false) =
        Chat(id = id, participants = users.toList(), unreadCount = unread, markedUnread = marked)

    @Test
    fun zeroUnreadOnlyTouchesTarget() {
        val chats = listOf(chat("a", unread = 3, marked = true), chat("b", unread = 1))
        val next = zeroChatUnread(chats, "a")
        assertEquals(0, next[0].unreadCount)
        assertEquals(false, next[0].markedUnread)
        assertEquals(1, next[1].unreadCount)
    }

    @Test
    fun zeroUnreadBlankChatIdIsNoop() {
        val chats = listOf(chat("a", unread = 3))
        val next = zeroChatUnread(chats, "  ")
        assertEquals(3, next[0].unreadCount)
    }

    @Test
    fun presenceOnlyUpdatesMatchingParticipant() {
        val chats = listOf(
            chat("c1", User(id = "u1", name = "A"), User(id = "u2", name = "B")),
            chat("c2", User(id = "u3", name = "C")),
        )
        val next = applyPresenceProjection(
            chats, userId = "u2", eventIsOnline = true, eventLastSeen = 999,
            onlineRevoked = false, statusRevoked = false,
        )
        assertEquals(false, next[0].participants[0].isOnline)
        assertEquals(true, next[0].participants[1].isOnline)
        assertEquals(999, next[0].participants[1].lastSeen)
        assertEquals(false, next[1].participants[0].isOnline)
    }

    @Test
    fun presenceRevokedForcesOffline() {
        val chats = listOf(chat("c1", User(id = "u1", name = "A", isOnline = true)))
        val next = applyPresenceProjection(
            chats, userId = "u1", eventIsOnline = true, eventLastSeen = 0,
            onlineRevoked = true, statusRevoked = false,
        )
        assertEquals(false, next[0].participants[0].isOnline)
    }

    @Test
    fun adminBroadcastBuildsItemAndBanner() {
        val p = buildAdminBroadcastProjection(
            title = "T", body = "  hello world  ", timestamp = 123L, senderLabel = "S"
        )!!
        assertEquals("admin_bc_123_${"hello world".hashCode()}", p.item.id)
        assertEquals("SECURITY", p.item.type)
        assertEquals("admin_broadcast_123", p.item.mergeKey)
        assertEquals("T", p.item.title)
        assertEquals("S", p.item.subtitle)
        assertEquals("hello world", p.item.preview)
        assertEquals("T: hello world", p.bannerText)
        assertEquals(mapOf("kind" to "admin_broadcast"), p.item.extra)
    }

    @Test
    fun adminBroadcastBlankBodyIsNull() {
        assertNull(buildAdminBroadcastProjection("T", "   ", 1L, "S"))
    }

    @Test
    fun adminBroadcastTruncatesLongBody() {
        val body = "x".repeat(500)
        val p = buildAdminBroadcastProjection("T", body, 1L, "S")!!
        assertEquals(200, p.item.preview!!.length)
        assertTrue(p.bannerText.length <= 240)
    }
}
