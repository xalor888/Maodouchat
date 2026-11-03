package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListReloadPolicyTest {

    @Test
    fun `user refresh and initial load show loading`() {
        assertEquals(Mode.IMMEDIATE_VISIBLE, ChatListReloadPolicy.modeFor(Trigger.USER_REFRESH))
        assertEquals(Mode.IMMEDIATE_VISIBLE, ChatListReloadPolicy.modeFor(Trigger.INITIAL))
        assertTrue(ChatListReloadPolicy.shouldShowLoading(Mode.IMMEDIATE_VISIBLE))
        assertEquals(0L, ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_VISIBLE))
    }

    @Test
    fun `reconnect is silent with its own debounce window`() {
        assertEquals(Mode.IMMEDIATE_SILENT, ChatListReloadPolicy.modeFor(Trigger.RECONNECT))
        assertFalse(ChatListReloadPolicy.shouldShowLoading(Mode.IMMEDIATE_SILENT))
        assertEquals(
            ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS,
            ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_SILENT, Trigger.RECONNECT),
        )
    }

    @Test
    fun `group revision bursts are coalesced silently`() {
        assertEquals(Mode.DEBOUNCED_SILENT, ChatListReloadPolicy.modeFor(Trigger.GROUP_REVISION))
        assertFalse(ChatListReloadPolicy.shouldShowLoading(Mode.DEBOUNCED_SILENT))
        assertEquals(
            ChatListReloadPolicy.DEFAULT_DEBOUNCE_MS,
            ChatListReloadPolicy.debounceMs(Mode.DEBOUNCED_SILENT),
        )
    }
}
