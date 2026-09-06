package com.maodouchat.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地好友关系缓存——只有服务端确认的好友（getFriends 成功返回 / 接受申请成功）
 * 才会进入联系人主列表。
 *
 * 具备特性：
 * 1. 账号隔离：支持以 ownerUserId 命名空间隔离，防切号数据串号。
 * 2. 响应式监听：提供 observeFriendIds Flow，支持与 Room 数据源 combine 实现全响应式更新。
 * 3. 离线持久化：基于 SharedPreferences，断网或重启后即刻展示本地可信好友列表。
 */
object FriendCacheStore {
    private const val PREFS = "maodouchat_friend_cache"
    private const val KEY_IDS = "friend_ids"

    private val memoryCache = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()

    private fun key(ownerUserId: String?): String =
        if (ownerUserId.isNullOrBlank()) KEY_IDS else "${KEY_IDS}_$ownerUserId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun observeFriendIds(context: Context, ownerUserId: String? = null): StateFlow<Set<String>> {
        val k = key(ownerUserId)
        return memoryCache.computeIfAbsent(k) {
            MutableStateFlow(getFriendIds(context, ownerUserId))
        }.asStateFlow()
    }

    fun getFriendIds(context: Context, ownerUserId: String? = null): Set<String> {
        val k = key(ownerUserId)
        val loaded = prefs(context).getStringSet(k, null)?.toSet()
        if (loaded != null) return loaded
        if (k != KEY_IDS) {
            return prefs(context).getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()
        }
        return emptySet()
    }

    /** 服务端好友列表全量刷新：原子替换缓存（登出清库时调用 clear）。 */
    fun replaceAll(context: Context, ids: Set<String>, ownerUserId: String? = null) {
        val k = key(ownerUserId)
        prefs(context).edit().putStringSet(k, ids).apply()
        if (k != KEY_IDS) {
            prefs(context).edit().putStringSet(KEY_IDS, ids).apply()
        }
        memoryCache[k]?.value = ids
        memoryCache[KEY_IDS]?.value = ids
    }

    fun add(context: Context, id: String, ownerUserId: String? = null) {
        if (id.isBlank()) return
        val updated = getFriendIds(context, ownerUserId) + id
        replaceAll(context, updated, ownerUserId)
    }

    fun remove(context: Context, id: String, ownerUserId: String? = null) {
        if (id.isBlank()) return
        val updated = getFriendIds(context, ownerUserId) - id
        replaceAll(context, updated, ownerUserId)
    }

    fun clear(context: Context, ownerUserId: String? = null) {
        val k = key(ownerUserId)
        prefs(context).edit().remove(k).apply()
        memoryCache[k]?.value = emptySet()
        if (k == KEY_IDS || ownerUserId.isNullOrBlank()) {
            memoryCache.clear()
            prefs(context).edit().clear().apply()
        }
    }
}
