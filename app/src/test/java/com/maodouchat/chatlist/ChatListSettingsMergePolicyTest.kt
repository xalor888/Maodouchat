package com.maodouchat.chatlist

import com.maodouchat.ui.screen.chatlist.ChatListSettingsMergePolicy
import com.maodouchat.ui.screen.chatlist.SettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    @Test
    fun serverWinsWhenLocalIsStale() {
        val server = SettingsSnapshot(
            pinnedAt = 10,
            notificationsMuted = true,
            archived = false,
            markedUnread = false,
            settingsUpdatedAt = 100
        )
        val local = SettingsSnapshot(
            pinnedAt = 50,
            notificationsMuted = false,
            archived = true,
            markedUnread = true,
            settingsUpdatedAt = 50
        )
        val merged = ChatListSettingsMergePolicy.merge(server, local)
        assertSame(server, merged)
        assertEquals(100, merged.settingsUpdatedAt)
        assertEquals(10, merged.pinnedAt)
    }

    @Test
    fun winnerIsReturnedByReference() {
        val server = SettingsSnapshot(1, true, true, true, 5)
        val local = SettingsSnapshot(2, false, false, false, 9)
        assertSame(local, ChatListSettingsMergePolicy.merge(server, local))
        assertSame(server, ChatListSettingsMergePolicy.merge(server, null))
    }

    @Test
    fun localWinsAtOneMillisecondBoundary() {
        val server = SettingsSnapshot(0, false, false, false, 100)
        val local = SettingsSnapshot(0, true, true, true, 101)
        val merged = ChatListSettingsMergePolicy.merge(server, local)
        assertSame(local, merged)
        assertTrue(merged.notificationsMuted)
    }

    @Test
    fun zeroTimestampsPreferServer() {
        val server = SettingsSnapshot(7, true, false, true, 0)
        val local = SettingsSnapshot(8, false, true, false, 0)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun negativeTimestampsStillCompareNumerically() {
        val server = SettingsSnapshot(0, false, false, false, -5)
        val local = SettingsSnapshot(0, true, true, true, -3)
        assertSame(local, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun serverWinsWhenLocalOnlyOneMsOlder() {
        val server = SettingsSnapshot(10, true, false, false, 100)
        val local = SettingsSnapshot(50, false, true, true, 99)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun localWinsAtLongMaxBoundary() {
        val server = SettingsSnapshot(10, true, false, false, Long.MAX_VALUE - 1)
        val local = SettingsSnapshot(50, false, true, true, Long.MAX_VALUE)
        assertSame(local, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun winnerFieldsAreNotMixed() {
        val server = SettingsSnapshot(1, true, false, true, 100)
        val local = SettingsSnapshot(2, false, true, false, 200)
        assertEquals(local, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun equalTimestampsReturnServerReference() {
        val server = SettingsSnapshot(10, true, false, false, 100)
        val local = SettingsSnapshot(50, false, true, true, 100)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun singleFieldDifferenceFollowsTimestamp() {
        val server = SettingsSnapshot(10, true, false, true, 100)
        val local = SettingsSnapshot(10, true, false, false, 200)
        assertFalse(ChatListSettingsMergePolicy.merge(server, local).markedUnread)
        val newerServer = SettingsSnapshot(10, true, false, true, 200)
        val olderLocal = SettingsSnapshot(10, true, false, false, 100)
        assertTrue(ChatListSettingsMergePolicy.merge(newerServer, olderLocal).markedUnread)
    }

    @Test
    fun localWinsAtLongMinBoundary() {
        val server = SettingsSnapshot(10, true, false, false, Long.MIN_VALUE)
        val local = SettingsSnapshot(50, false, true, true, Long.MIN_VALUE + 1)
        assertSame(local, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun equalTimestampsAtLongMinPreferServer() {
        val server = SettingsSnapshot(10, true, false, false, Long.MIN_VALUE)
        val local = SettingsSnapshot(50, false, true, true, Long.MIN_VALUE)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun serverWinsWhenLocalTimestampIsNegativeAndServerZero() {
        val server = SettingsSnapshot(10, true, false, false, 0)
        val local = SettingsSnapshot(50, false, true, true, -1)
        assertSame(server, ChatListSettingsMergePolicy.merge(server, local))
    }

    @Test
    fun equalTimestampsPreferServerEvenWhenAllFieldsDiffer() {
        val server = SettingsSnapshot(1, false, false, false, 100)
        val local = SettingsSnapshot(2, true, true, true, 100)
        val merged = ChatListSettingsMergePolicy.merge(server, local)
        assertSame(server, merged)
        assertFalse(merged.notificationsMuted)
        assertFalse(merged.archived)
        assertFalse(merged.markedUnread)
    }

    @Test
    fun mergeLeavesBothSnapshotsUnchanged() {
        val server = SettingsSnapshot(10, true, false, true, 100)
        val local = SettingsSnapshot(20, false, true, false, 200)
        ChatListSettingsMergePolicy.merge(server, local)
        assertEquals(SettingsSnapshot(10, true, false, true, 100), server)
        assertEquals(SettingsSnapshot(20, false, true, false, 200), local)
    }

}
