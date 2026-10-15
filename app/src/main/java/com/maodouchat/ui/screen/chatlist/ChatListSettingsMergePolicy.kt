package com.maodouchat.ui.screen.chatlist

/**
 * Prefer local optimistic chat settings when getChats races with an in-flight
 * updateChatSettings (local settingsUpdatedAt is newer than server).
 */
object ChatListSettingsMergePolicy {

    /**
     * @return server snapshot when local is null/stale; local when local.updatedAt is strictly newer.
     * Equal timestamps prefer server (authoritative after confirmed REST).
     */
    fun merge(server: SettingsSnapshot, local: SettingsSnapshot?): SettingsSnapshot {
        if (local == null) return server
        return if (local.settingsUpdatedAt > server.settingsUpdatedAt) local else server
    }
}
