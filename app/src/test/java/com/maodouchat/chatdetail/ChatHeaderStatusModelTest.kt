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
}
