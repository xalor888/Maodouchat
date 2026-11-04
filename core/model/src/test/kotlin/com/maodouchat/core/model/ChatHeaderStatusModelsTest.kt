package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatHeaderStatusModelsTest {

    @Test
    fun `all six header states are distinct`() {
        val states = listOf(
            ChatHeaderStatus.Typing("u1"),
            ChatHeaderStatus.Online,
            ChatHeaderStatus.Offline,
            ChatHeaderStatus.LastSeen(42L),
            ChatHeaderStatus.Custom("Busy"),
            ChatHeaderStatus.None,
        )
        assertEquals(6, states.distinct().size)
    }

    @Test
    fun `defaults keep one-to-one offline without last seen`() {
        assertEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = ""),
        )
        assertNotEquals(
            ChatHeaderStatus.Offline,
            resolveChatHeaderStatus(null, isOnline = false, customStatus = "", isGroup = true),
        )
    }

    @Test
    fun `typing id is trimmed before it becomes state`() {
        assertEquals(
            ChatHeaderStatus.Typing("u1"),
            resolveChatHeaderStatus("  u1  ", isOnline = false, customStatus = ""),
        )
    }
}
