package com.maodouchat.chatlist

import com.maodouchat.ui.screen.chatlist.ChatListSettingsMergePolicy
import com.maodouchat.ui.screen.chatlist.ChatListSettingsMergePolicy.SettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListSettingsMergePolicyTest {

    @Test
    fun prefersLocalWhenNewer() {
        val server = SettingsSnapshot(
            pinnedAt = 0,
            notificationsMuted = false,
            archived = false,
            markedUnread = false,
            settingsUpdatedAt = 100
        )
        val local = SettingsSnapshot(
            pinnedAt = 50,
            notificationsMuted = true,
            archived = true,
            markedUnread = true,
            settingsUpdatedAt = 200
        )
        val merged = ChatListSettingsMergePolicy.merge(server, local)
        assertEquals(50, merged.pinnedAt)
        assertTrue(merged.notificationsMuted)
        assertTrue(merged.archived)
        assertTrue(merged.markedUnread)
        assertEquals(200, merged.settingsUpdatedAt)
    }

    @Test
    fun prefersServerWhenEqualOrNewer() {
        val server = SettingsSnapshot(
            pinnedAt = 10,
            notificationsMuted = true,
            archived = false,
            markedUnread = false,
            settingsUpdatedAt = 100
        )
        val local = SettingsSnapshot(
            pinnedAt = 0,
            notificationsMuted = false,
            archived = true,
            markedUnread = true,
            settingsUpdatedAt = 100
        )
        val merged = ChatListSettingsMergePolicy.merge(server, local)
        assertEquals(10, merged.pinnedAt)
        assertTrue(merged.notificationsMuted)
        assertFalse(merged.archived)
        assertFalse(merged.markedUnread)
    }

    @Test
    fun nullLocalUsesServer() {
        val server = SettingsSnapshot(1, true, true, true, 5)
        assertEquals(server, ChatListSettingsMergePolicy.merge(server, null))
    }
}
