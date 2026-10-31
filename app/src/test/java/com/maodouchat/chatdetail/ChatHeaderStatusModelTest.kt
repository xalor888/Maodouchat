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

    @Test
    fun `typing wins when every other signal is on in a group`() {
        assertEquals(
            ChatHeaderStatus.Typing("u7"),
            resolveChatHeaderStatus("u7", isOnline = true, customStatus = "Busy", isGroup = true, lastSeen = 999L)
        )
    }

    @Test
    fun `online outranks lastSeen`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus(null, isOnline = true, customStatus = "  ", isGroup = false, lastSeen = 999L)
        )
    }

    @Test
    fun `custom status outranks lastSeen in both chat kinds`() {
        assertEquals(
            ChatHeaderStatus.Custom("Busy"),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "Busy", isGroup = false, lastSeen = 999L)
        )
        assertEquals(
            ChatHeaderStatus.Custom("Busy"),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "Busy", isGroup = true, lastSeen = 999L)
        )
    }

    @Test
    fun `max lastSeen is shown verbatim`() {
        assertEquals(
            ChatHeaderStatus.LastSeen(Long.MAX_VALUE),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = false, lastSeen = Long.MAX_VALUE)
        )
    }

    @Test
    fun `typing id trims ends only and keeps inner space`() {
        assertEquals(
            ChatHeaderStatus.Typing("u 2"),
            resolveChatHeaderStatus("  u 2  ", isOnline = false, customStatus = "")
        )
    }

    @Test
    fun `typing id with tabs and newline trims to inner id`() {
        assertEquals(
            ChatHeaderStatus.Typing("u4"),
            resolveChatHeaderStatus(" \tu4\n ", isOnline = false, customStatus = "")
        )
    }

    @Test
    fun `group negative lastSeen returns none instead of offline`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true, lastSeen = -5L)
        )
    }

    @Test
    fun `group online outranks custom status and lastSeen`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus(null, isOnline = true, customStatus = "Busy", isGroup = true, lastSeen = 999L)
        )
    }

    @Test
    fun `tab-only custom status falls through to lastSeen`() {
        assertEquals(
            ChatHeaderStatus.LastSeen(42L),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "\t ", lastSeen = 42L)
        )
    }

    @Test
    fun `lastSeen of one shows lastSeen`() {
        assertEquals(
            ChatHeaderStatus.LastSeen(1L),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", lastSeen = 1L)
        )
    }

    @Test
    fun `non-breaking-space padded typing id is trimmed to the bare id`() {
        // Kotlin trim()/isWhitespace() treat NBSP as whitespace, unlike Java's trim().
        assertEquals(
            ChatHeaderStatus.Typing("u5"),
            resolveChatHeaderStatus("\u00a0u5\u00a0", isOnline = false, customStatus = "")
        )
    }

    @Test
    fun `typing id with an internal newline is kept`() {
        assertEquals(
            ChatHeaderStatus.Typing("u\n2"),
            resolveChatHeaderStatus("u\n2", isOnline = false, customStatus = "")
        )
    }

    @Test
    fun `mixed whitespace custom status falls through to lastSeen`() {
        assertEquals(
            ChatHeaderStatus.LastSeen(42L),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "\r\n\t ", lastSeen = 42L)
        )
    }

    @Test
    fun `non-breaking-space-only custom status counts as blank`() {
        // Kotlin isBlank() treats NBSP as whitespace, so it falls through to offline.
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "\u00a0")
        )
    }

    @Test
    fun `group with min lastSeen returns none`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true, lastSeen = Long.MIN_VALUE)
        )
    }


    @Test
    fun `one-to-one min lastSeen falls back to binary offline`() {
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", lastSeen = Long.MIN_VALUE)
        )
    }

    @Test
    fun `group positive lastSeen still returns none`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true, lastSeen = 42L)
        )
    }

    @Test
    fun `nbsp padded custom status is returned verbatim`() {
        assertEquals(
            ChatHeaderStatus.Custom("\u00a0Busy\u00a0"),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "\u00a0Busy\u00a0")
        )
    }

    @Test
    fun `nbsp-only typing id falls through to online`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus("\u00a0", isOnline = true, customStatus = "")
        )
    }

    @Test
    fun `nbsp-only custom status falls through to lastSeen`() {
        assertEquals(
            ChatHeaderStatus.LastSeen(42L),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "\u00a0", lastSeen = 42L)
        )
    }

    @Test
    fun `group with zero lastSeen returns none`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true, lastSeen = 0)
        )
    }

    @Test
    fun `nbsp padded typing id still wins over online`() {
        assertEquals(
            ChatHeaderStatus.Typing("u5"),
            resolveChatHeaderStatus("\u00a0u5\u00a0", isOnline = true, customStatus = "Busy")
        )
    }

    @Test
    fun `group nbsp-only custom status with lastSeen returns none`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "\u00a0", isGroup = true, lastSeen = 42L)
        )
    }

    @Test
    fun `single zero custom status is returned verbatim`() {
        assertEquals(
            ChatHeaderStatus.Custom("0"),
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "0")
        )
    }

    @Test
    fun `empty typing id falls through to custom status`() {
        assertEquals(
            ChatHeaderStatus.Custom("Busy"),
            resolveChatHeaderStatus("", isOnline = false, customStatus = "Busy")
        )
    }

    @Test
    fun `group with no signals returns none`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true)
        )
    }

    @Test
    fun `one-to-one with no signals and default lastSeen returns offline`() {
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ")
        )
    }

    @Test
    fun `nbsp-only typing id falls through to custom status`() {
        assertEquals(
            ChatHeaderStatus.Custom("Busy"),
            resolveChatHeaderStatus("\u00a0", isOnline = false, customStatus = "Busy")
        )
    }

    @Test
    fun `group max lastSeen still returns none`() {
        assertEquals(
            ChatHeaderStatus.None,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "  ", isGroup = true, lastSeen = Long.MAX_VALUE)
        )
    }

    @Test
    fun `tab and newline only typing id falls through to online`() {
        assertEquals(
            ChatHeaderStatus.Online,
            resolveChatHeaderStatus(" \t\n ", isOnline = true, customStatus = "")
        )
    }
}
