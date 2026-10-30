package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.ChatHeaderStatus
import com.maodouchat.ui.screen.chatdetail.resolveChatHeaderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderStatusModelTest {
    @Test
    fun `typing takes priority over online presence`() {
        assertEquals(
            ChatHeaderStatus.Typing("user-2"),
            resolveChatHeaderStatus("user-2", isOnline = true, customStatus = "Available")
        )
    }

    @Test
    fun `online takes priority over custom status`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus(null, isOnline = true, customStatus = "Available")
        )
    }

    @Test
    fun `custom and empty states are resolved defensively`() {
        assertEquals(
            ChatHeaderStatus.Custom("Busy"),
            resolveChatHeaderStatus("  ", isOnline = false, customStatus = "Busy")
        )
        // 1:1 shows binary offline when no custom status (no last-seen protocol).
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ")
        )
        // Groups do not imply whole-group offline.
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true)
        )
    }

    @Test
    fun `typing id is trimmed before priority check`() {
        assertEquals(
            ChatHeaderStatus.Typing("u2"),
            resolveChatHeaderStatus("  u2  ", isOnline = false, customStatus = "")
        )
    }

    @Test
    fun `typing outranks online custom status and lastSeen`() {
        assertEquals(
            ChatHeaderStatus.Typing("u9"),
            resolveChatHeaderStatus("u9", isOnline = true, customStatus = "Busy", lastSeen = 123L)
        )
    }

    @Test
    fun `lastSeen is shown for one-to-one chats only`() {
        assertEquals(
            ChatHeaderStatus.LastSeen(999L),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = false, lastSeen = 999L)
        )
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true, lastSeen = 999L)
        )
    }

    @Test
    fun `zero lastSeen falls back to binary offline`() {
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = false, lastSeen = 0)
        )
    }

    @Test
    fun `custom status text is returned verbatim`() {
        // Regression pin: blank check only, no trimming.
        assertEquals(
            ChatHeaderStatus.Custom("  Busy  "),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  Busy  ")
        )
    }

    @Test
    fun `blank typing id falls through to online`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus("   ", isOnline = true, customStatus = "")
        )
    }

    @Test
    fun `negative lastSeen falls back to binary offline`() {
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = false, lastSeen = -5L)
        )
    }

    @Test
    fun `group online still shows online`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus(null, isOnline = true, customStatus = "", isGroup = true)
        )
    }

    @Test
    fun `group custom status still shows custom`() {
        assertEquals(
            ChatHeaderStatus.Custom("Busy"),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "Busy", isGroup = true)
        )
    }

    @Test
    fun `typing outranks group none`() {
        assertEquals(
            ChatHeaderStatus.Typing("u3"),
            resolveChatHeaderStatus("u3", isOnline = false, customStatus = "", isGroup = true)
        )
    }
}
