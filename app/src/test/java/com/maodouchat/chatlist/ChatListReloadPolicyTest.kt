package com.maodouchat.chatlist

import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy
import com.maodouchat.ui.screen.chatlist.Mode
import com.maodouchat.ui.screen.chatlist.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListReloadPolicyTest {

    @Test
    fun userAndInitialAreImmediateVisible() {
        assertEquals(Mode.IMMEDIATE_VISIBLE, ChatListReloadPolicy.modeFor(Trigger.USER_REFRESH))
        assertEquals(Mode.IMMEDIATE_VISIBLE, ChatListReloadPolicy.modeFor(Trigger.INITIAL))
        assertTrue(ChatListReloadPolicy.shouldShowLoading(Mode.IMMEDIATE_VISIBLE))
        assertEquals(0L, ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_VISIBLE))
    }

    @Test
    fun reconnectAndForegroundAreImmediateSilent() {
        assertEquals(Mode.IMMEDIATE_SILENT, ChatListReloadPolicy.modeFor(Trigger.RECONNECT))
        assertEquals(Mode.IMMEDIATE_SILENT, ChatListReloadPolicy.modeFor(Trigger.FOREGROUND))
        assertFalse(ChatListReloadPolicy.shouldShowLoading(Mode.IMMEDIATE_SILENT))
        assertEquals(
            ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS,
            ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_SILENT, Trigger.RECONNECT)
        )
        assertEquals(0L, ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_SILENT, Trigger.FOREGROUND))
    }

    @Test
    fun groupRevisionIsDebouncedSilent() {
        val mode = ChatListReloadPolicy.modeFor(Trigger.GROUP_REVISION)
        assertEquals(Mode.DEBOUNCED_SILENT, mode)
        assertFalse(ChatListReloadPolicy.shouldShowLoading(mode))
        assertEquals(ChatListReloadPolicy.DEFAULT_DEBOUNCE_MS, ChatListReloadPolicy.debounceMs(mode))
    }

    @Test
    fun debounceConstantsArePinned() {
        assertEquals(400L, ChatListReloadPolicy.DEFAULT_DEBOUNCE_MS)
        assertEquals(800L, ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS)
    }

    @Test
    fun reconnectTriggerForcesDebounceRegardlessOfMode() {
        assertEquals(
            ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS,
            ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_VISIBLE, Trigger.RECONNECT)
        )
        assertEquals(
            ChatListReloadPolicy.RECONNECT_DEBOUNCE_MS,
            ChatListReloadPolicy.debounceMs(Mode.DEBOUNCED_SILENT, Trigger.RECONNECT)
        )
    }

    @Test
    fun nonReconnectTriggersSkipDebounceOnSilentModes() {
        assertEquals(0L, ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_SILENT, Trigger.USER_REFRESH))
        assertEquals(0L, ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_SILENT, Trigger.INITIAL))
        assertEquals(0L, ChatListReloadPolicy.debounceMs(Mode.IMMEDIATE_SILENT))
    }

    @Test
    fun debouncedSilentKeepsDefaultDebounceForAnyTrigger() {
        assertEquals(
            ChatListReloadPolicy.DEFAULT_DEBOUNCE_MS,
            ChatListReloadPolicy.debounceMs(Mode.DEBOUNCED_SILENT, Trigger.USER_REFRESH)
        )
        assertFalse(ChatListReloadPolicy.shouldShowLoading(Mode.DEBOUNCED_SILENT))
    }

    @Test
    fun everyTriggerHasAnExplicitMode() {
        assertEquals(5, Trigger.values().size)
        Trigger.values().forEach { trigger ->
            val mode = ChatListReloadPolicy.modeFor(trigger)
            assertTrue(
                mode == Mode.IMMEDIATE_VISIBLE || mode == Mode.IMMEDIATE_SILENT ||
                    mode == Mode.DEBOUNCED_SILENT
            )
        }
    }
}
