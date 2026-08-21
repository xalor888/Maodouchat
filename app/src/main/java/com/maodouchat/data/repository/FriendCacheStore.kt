package com.maodouchat.data.repository

import android.content.Context

/**
 * 9.3xx：本地好友关系缓存——只有服务端确认的好友（getFriends 成功返回 / 接受申请成功）
 * 才会进入联系人主列表。
 *
 * 此前 loadContacts 在 getFriends 失败（429/断网）时回退到 userRepo.getAllUsers()，
 * 而本地用户表缓存了所有会话参与者（群成员、仅聊过天的陌生人），导致"没同意就成为好友"。
 * 现在失败时只展示本缓存内的好友 ID；缓存为空则显示错误与重试，绝不把陌生人当好友。
 */
object FriendCacheStore {
    private const val PREFS = "maodouchat_friend_cache"
    private const val KEY_IDS = "friend_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFriendIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()

    /** 服务端好友列表全量刷新：原子替换缓存（登出清库时调用 clear）。 */
    fun replaceAll(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_IDS, ids).apply()
    }

    fun add(context: Context, id: String) {
        if (id.isBlank()) return
        val updated = getFriendIds(context) + id
        prefs(context).edit().putStringSet(KEY_IDS, updated).apply()
    }

    fun remove(context: Context, id: String) {
        if (id.isBlank()) return
        val updated = getFriendIds(context) - id
        prefs(context).edit().putStringSet(KEY_IDS, updated).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IDS).apply()
    }
}
