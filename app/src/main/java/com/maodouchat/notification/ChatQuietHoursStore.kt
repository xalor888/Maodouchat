package com.maodouchat.notification

import android.content.Context
import com.maodouchat.network.TokenManager
import org.json.JSONObject

/**
 * 会话级「免打扰时段」（8.46）：按账号本地保存每个会话的静音时间窗，
 * 与全局 DND 互补（TG 风格 per-chat schedule）。纯本机功能，不触碰服务端。
 */
object ChatQuietHoursStore {

    private const val PREFS_NAME = "chat_quiet_hours"
    private const val KEY_QUIET_HOURS = "quiet_hours"

    // 读-改-写整串 JSON 必须互斥：UI 设置时段与 FCM 推送路径并发时，
    // 后写者整串覆盖会让先写者的修改静默丢失（竞态）。
    private val lock = Any()

    data class QuietWindow(
        val enabled: Boolean,
        val startMinute: Int,
        val endMinute: Int
    ) {
        companion object {
            val OFF = QuietWindow(false, 0, 0)
        }
    }

    fun get(context: Context, chatId: String): QuietWindow {
        val userId = currentUserId(context) ?: return QuietWindow.OFF
        if (chatId.isBlank()) return QuietWindow.OFF
        return runCatching {
            val raw = prefs(context).getString(key(userId), null) ?: return QuietWindow.OFF
            val obj = JSONObject(raw)
            if (!obj.has(chatId)) return QuietWindow.OFF
            val w = obj.getJSONObject(chatId)
            val start = w.optInt("start", 0).coerceIn(0, 1439)
            val end = w.optInt("end", 0).coerceIn(0, 1439)
            QuietWindow(enabled = w.optBoolean("enabled", false), startMinute = start, endMinute = end)
        }.getOrDefault(QuietWindow.OFF)
    }

    fun set(context: Context, chatId: String, window: QuietWindow) {
        val userId = currentUserId(context) ?: return
        if (chatId.isBlank()) return
        synchronized(lock) {
            runCatching {
                val raw = prefs(context).getString(key(userId), null)
                val obj = if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
                val existing = obj.optJSONObject(chatId)
                val entry = JSONObject()
                    .put("enabled", window.enabled)
                    .put("start", window.startMinute.coerceIn(0, 1439))
                    .put("end", window.endMinute.coerceIn(0, 1439))
                // 1.40：保留同条目内已有的临时静音至字段（1.02 语义为「与时段共存」）
                existing?.optLong("silent_until", 0L)?.takeIf { it > 0L }?.let { entry.put("silent_until", it) }
                obj.put(chatId, entry)
                prefs(context).edit().putString(key(userId), obj.toString()).apply()
            }
        }
    }

    /** 清除该会话的免打扰时段（enabled=false 亦可表达关闭，此处物理删除）。 */
    fun remove(context: Context, chatId: String) {
        val userId = currentUserId(context) ?: return
        if (chatId.isBlank()) return
        synchronized(lock) {
            runCatching {
                val raw = prefs(context).getString(key(userId), null) ?: return@runCatching
                val obj = JSONObject(raw)
                if (obj.has(chatId)) {
                    obj.remove(chatId)
                    prefs(context).edit().putString(key(userId), obj.toString()).apply()
                }
            }
        }
    }

    /** 1.02：设置会话「临时静音至」时间戳（untilMs<=0 清除）。与时段共存（同一条目）。 */
    fun setSilentUntil(context: Context, chatId: String, untilMs: Long) {
        val userId = currentUserId(context) ?: return
        if (chatId.isBlank()) return
        synchronized(lock) {
            runCatching {
                val raw = prefs(context).getString(key(userId), null)
                val obj = if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
                val existing = obj.optJSONObject(chatId)
                val entry = if (existing != null) existing else JSONObject()
                if (untilMs <= 0) entry.remove("silent_until") else entry.put("silent_until", untilMs)
                if (entry.length() == 0) obj.remove(chatId) else obj.put(chatId, entry)
                prefs(context).edit().putString(key(userId), obj.toString()).apply()
            }
        }
    }

    /** 1.02：读取会话「临时静音至」时间戳（0 = 未设置/已过期）。 */
    fun silentUntil(context: Context, chatId: String): Long {
        val userId = currentUserId(context) ?: return 0L
        if (chatId.isBlank()) return 0L
        return runCatching {
            val raw = prefs(context).getString(key(userId), null) ?: return 0L
            val obj = JSONObject(raw)
            obj.optJSONObject(chatId)?.optLong("silent_until", 0L) ?: 0L
        }.getOrDefault(0L)
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(userId)).apply()
    }

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(userId: String): String = "${KEY_QUIET_HOURS}_$userId"
}
