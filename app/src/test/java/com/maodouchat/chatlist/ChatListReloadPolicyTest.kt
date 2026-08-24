package com.maodouchat.chatlist

import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy
import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy.Mode
import com.maodouchat.ui.screen.chatlist.ChatListReloadPolicy.Trigger
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
    fun deleteRevokeGroupRevisionAreDebouncedSilent() {
        for (trigger in listOf(
            Trigger.MESSAGE_DELETED,
            Trigger.MESSAGE_REVOKED,
            Trigger.GROUP_REVISION
        )) {
            val mode = ChatListReloadPolicy.modeFor(trigger)
            assertEquals(Mode.DEBOUNCED_SILENT, mode)
            assertFalse(ChatListReloadPolicy.shouldShowLoading(mode))
            assertEquals(ChatListReloadPolicy.DEFAULT_DEBOUNCE_MS, ChatListReloadPolicy.debounceMs(mode))
        }
    }

    @Test
    fun unknownChatMessageIsImmediateSilent() {
        val mode = ChatListReloadPolicy.modeFor(Trigger.UNKNOWN_CHAT_MESSAGE)
        assertEquals(Mode.IMMEDIATE_SILENT, mode)
        assertFalse(ChatListReloadPolicy.shouldShowLoading(mode))
        assertEquals(0L, ChatListReloadPolicy.debounceMs(mode))
    }
}
