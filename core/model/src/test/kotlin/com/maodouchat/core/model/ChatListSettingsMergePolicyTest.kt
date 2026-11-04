package com.maodouchat.core.model

import org.junit.Assert.assertSame
import org.junit.Test

class ChatListSettingsMergePolicyTest {

    @Test
    fun `null local falls back to server`() {
        val server = SettingsSnapshot(1, true, true, true, 5)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, null))
    }

    @Test
    fun `newer local wins`() {
        val server = SettingsSnapshot(1, true, true, true, 5)
        val local = SettingsSnapshot(2, false, false, false, 9)
        assertSame(local, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun `equal timestamps prefer server`() {
        val server = SettingsSnapshot(7, true, false, true, 0)
        val local = SettingsSnapshot(8, false, true, false, 0)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun `stale local keeps server`() {
        val server = SettingsSnapshot(0, false, false, false, 100)
        val local = SettingsSnapshot(0, true, true, true, 99)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }
}
