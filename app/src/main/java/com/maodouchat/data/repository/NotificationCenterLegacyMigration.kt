package com.maodouchat.data.repository

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.NotificationCenterItemEntity
import com.maodouchat.data.local.entity.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 通知中心旧 SharedPreferences 快照的一次性导入迁移（`notification_center` 键已退役）。
 * Room 为空且 prefs 有数据时搬运后删键；此后 prefs 不再被读写。
 */
internal class NotificationCenterLegacyMigration(
    private val appContext: Context,
    private val database: AppDatabase,
) {
    fun migrateIfNeeded(userId: String) {
        if (roomItems(userId).isNotEmpty()) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (key in listOf(accountItemsKey(userId), KEY_ITEMS)) {
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: continue
            val items = runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { index ->
                    runCatching { NotificationCenterItem.fromJson(arr.getJSONObject(index)) }.getOrNull()
                }
            }.getOrNull().orEmpty()
            if (items.isNotEmpty()) {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        database.notificationCenterDao().replaceAllBlocking(userId, items.map { it.toEntity(userId) })
                    }
                }
            }
            runCatching { prefs.edit { remove(key) } }
            if (key == accountItemsKey(userId)) break
        }
    }

    private fun roomItems(userId: String): List<NotificationCenterItemEntity> = runCatching {
        runBlocking(Dispatchers.IO) {
            database.notificationCenterDao().itemsForOwnerBlocking(userId)
        }
    }.getOrDefault(emptyList())

    private fun accountItemsKey(userId: String): String = "$KEY_ITEMS:$userId"

    companion object {
        private const val PREFS = "notification_center"
        private const val KEY_ITEMS = "items"
    }
}
