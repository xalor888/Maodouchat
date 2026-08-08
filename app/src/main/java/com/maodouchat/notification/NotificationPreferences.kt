package com.maodouchat.notification

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.TokenManager

/**
 * Local notification preferences scoped to the currently authenticated account.
 *
 * The server already stores its notification settings per user. Keeping the local
 * fallback global would leak the previous account's preview/DND choices during an
 * account switch and could schedule reminders with the wrong policy.
 */
object NotificationPreferences {
    const val PREFS_NAME = "notification_settings"
    const val KEY_ENABLE = "enable"
    const val KEY_SOUND = "sound"
    const val KEY_VIBRATION = "vibration"
    const val KEY_PREVIEW = "preview"
    const val KEY_RINGTONE = "ringtone"
    const val KEY_RINGTONE_URI = "ringtone_uri"
    /** 0.72：群聊独立通知铃声（本机，与单聊铃声分离）。 */
    const val KEY_GROUP_RINGTONE_URI = "group_ringtone_uri"
    const val KEY_TASK_REMINDERS = "task_reminders"
    const val KEY_DND_START = "dnd_start"
    const val KEY_DND_END = "dnd_end"
    /** 勿扰计划：显式开关 + 分钟级窗口（0-1439）。旧小时级 key 保留作兼容读取。 */
    const val KEY_DND_ENABLED = "dnd_enabled"
    const val KEY_DND_START_MINUTE = "dnd_start_minute"
    const val KEY_DND_END_MINUTE = "dnd_end_minute"
    /** 8.34：本地存在未同步到服务端的设置修改（sync 失败持久化标记，防刷新静默回滚）。 */
    const val KEY_PENDING_SYNC = "pending_sync"

    private const val KEY_MIGRATED = "account_scope_migrated"
    private val BOOLEAN_KEYS = listOf(KEY_ENABLE, KEY_SOUND, KEY_VIBRATION, KEY_PREVIEW, KEY_RINGTONE, KEY_TASK_REMINDERS)
    private val INT_KEYS = listOf(KEY_DND_START, KEY_DND_END)
    private val migrationLock = Any()

    internal fun scopedKey(base: String, userId: String): String = "$base:$userId"

    fun notificationsEnabled(context: Context): Boolean = getBoolean(context, KEY_ENABLE, true)
    fun soundEnabled(context: Context): Boolean = getBoolean(context, KEY_SOUND, true)
    fun vibrationEnabled(context: Context): Boolean = getBoolean(context, KEY_VIBRATION, true)
    fun previewEnabled(context: Context): Boolean = getBoolean(context, KEY_PREVIEW, true)
    fun ringtoneEnabled(context: Context): Boolean = getBoolean(context, KEY_RINGTONE, true)
    fun taskRemindersEnabled(context: Context): Boolean = getBoolean(context, KEY_TASK_REMINDERS, true)
    fun dndStartHour(context: Context): Int = getInt(context, KEY_DND_START, 22).coerceIn(0, 23)
    fun dndEndHour(context: Context): Int = getInt(context, KEY_DND_END, 7).coerceIn(0, 23)

    // ---- 通知铃声（8.48：可选系统铃声，空 = 渠道默认） ----

    /** 用户选择的铃声 URI（字符串）；空/默认 = 系统默认铃声。 */
    fun ringtoneUri(context: Context): String? {
        val account = accountPreferences(context) ?: return null
        return account.prefs.getString(scopedKey(KEY_RINGTONE_URI, account.userId), null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 设置/清除自定义铃声（null/空 = 恢复系统默认）。 */
    fun setRingtoneUri(context: Context, uri: String?) {
        val account = accountPreferences(context) ?: return
        val editor = account.prefs.edit()
        if (uri.isNullOrBlank()) {
            editor.remove(scopedKey(KEY_RINGTONE_URI, account.userId))
        } else {
            editor.putString(scopedKey(KEY_RINGTONE_URI, account.userId), uri)
        }
        editor.apply()
    }

    /** 0.72：群聊独立铃声 URI；空 = 回退单聊铃声。 */
    fun groupRingtoneUri(context: Context): String? {
        val account = accountPreferences(context) ?: return null
        return account.prefs.getString(scopedKey(KEY_GROUP_RINGTONE_URI, account.userId), null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 0.72：设置/清除群聊铃声（null/空 = 恢复默认，回退单聊铃声）。 */
    fun setGroupRingtoneUri(context: Context, uri: String?) {
        val account = accountPreferences(context) ?: return
        val editor = account.prefs.edit()
        if (uri.isNullOrBlank()) {
            editor.remove(scopedKey(KEY_GROUP_RINGTONE_URI, account.userId))
        } else {
            editor.putString(scopedKey(KEY_GROUP_RINGTONE_URI, account.userId), uri)
        }
        editor.apply()
    }

    // ---- 勿扰计划（DND schedule）：分钟级 + 显式开关 ----

    /** 勿扰计划是否启用。默认 false = 未设置计划（与旧版"关闭 DND"语义一致）。 */
    fun dndEnabled(context: Context): Boolean = getBoolean(context, KEY_DND_ENABLED, false)

    /** 勿扰开始分钟（0-1439）。默认 22:00。 */
    fun dndStartMinute(context: Context): Int = getInt(context, KEY_DND_START_MINUTE, 22 * 60).coerceIn(0, 1439)

    /** 勿扰结束分钟（0-1439）。默认 07:00。 */
    fun dndEndMinute(context: Context): Int = getInt(context, KEY_DND_END_MINUTE, 7 * 60).coerceIn(0, 1439)

    /**
     * 迁移并保存勿扰计划。写入分钟级字段与开关；同时回写小时级 key，
     * 保证旧逻辑（AiTaskReminderPreferences / 服务端小时级字段）一致。
     */
    fun saveDndSchedule(context: Context, enabled: Boolean, startMinute: Int, endMinute: Int) {
        val account = accountPreferences(context) ?: return
        val safeStart = startMinute.coerceIn(0, 1439)
        val safeEnd = endMinute.coerceIn(0, 1439)
        account.prefs.edit()
            .putBoolean(scopedKey(KEY_DND_ENABLED, account.userId), enabled)
            .putInt(scopedKey(KEY_DND_START_MINUTE, account.userId), safeStart)
            .putInt(scopedKey(KEY_DND_END_MINUTE, account.userId), safeEnd)
            .putInt(scopedKey(KEY_DND_START, account.userId), safeStart / 60)
            .putInt(scopedKey(KEY_DND_END, account.userId), safeEnd / 60)
            .apply()
    }

    fun setTaskRemindersEnabled(context: Context, enabled: Boolean) {
        putBoolean(context, KEY_TASK_REMINDERS, enabled)
    }

    /** 1.133：通知震动开关（渠道级生效，改动后需重新 ensureChannels）。 */
    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        putBoolean(context, KEY_VIBRATION, enabled)
    }

    fun save(
        context: Context,
        enableNotifications: Boolean,
        soundEnabled: Boolean,
        previewEnabled: Boolean,
        ringtoneEnabled: Boolean,
        dndStartHour: Int,
        dndEndHour: Int,
        taskRemindersEnabled: Boolean? = null,
        dndEnabled: Boolean? = null,
        dndStartMinute: Int? = null,
        dndEndMinute: Int? = null
    ) {
        val account = accountPreferences(context) ?: return
        val startMinute = dndStartMinute?.coerceIn(0, 1439) ?: (dndStartHour.coerceIn(0, 23) * 60)
        val endMinute = dndEndMinute?.coerceIn(0, 1439) ?: (dndEndHour.coerceIn(0, 23) * 60)
        val editor = account.prefs.edit()
        editor.putBoolean(scopedKey(KEY_ENABLE, account.userId), enableNotifications)
            .putBoolean(scopedKey(KEY_SOUND, account.userId), soundEnabled)
            .putBoolean(scopedKey(KEY_PREVIEW, account.userId), previewEnabled)
            .putBoolean(scopedKey(KEY_RINGTONE, account.userId), ringtoneEnabled)
            .putInt(scopedKey(KEY_DND_START, account.userId), startMinute / 60)
            .putInt(scopedKey(KEY_DND_END, account.userId), endMinute / 60)
            .putBoolean(scopedKey(KEY_DND_ENABLED, account.userId), dndEnabled ?: false)
            .putInt(scopedKey(KEY_DND_START_MINUTE, account.userId), startMinute)
            .putInt(scopedKey(KEY_DND_END_MINUTE, account.userId), endMinute)
        taskRemindersEnabled?.let {
            editor.putBoolean(scopedKey(KEY_TASK_REMINDERS, account.userId), it)
        }
        editor.apply()
    }

    private fun getBoolean(context: Context, key: String, default: Boolean): Boolean {
        val account = accountPreferences(context) ?: return default
        return account.prefs.getBoolean(scopedKey(key, account.userId), default)
    }

    /** 8.34：本地是否有未同步的设置修改（sync 失败时置位，成功后清除）。 */
    fun hasPendingSync(context: Context): Boolean = getBoolean(context, KEY_PENDING_SYNC, false)

    fun markPendingSync(context: Context, pending: Boolean) {
        putBoolean(context, KEY_PENDING_SYNC, pending)
    }

    private fun getInt(context: Context, key: String, default: Int): Int {
        val account = accountPreferences(context) ?: return default
        return account.prefs.getInt(scopedKey(key, account.userId), default)
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        val account = accountPreferences(context) ?: return
        account.prefs.edit().putBoolean(scopedKey(key, account.userId), value).apply()
    }

    private fun accountPreferences(context: Context): AccountPreferences? {
        val appContext = context.applicationContext
        val userId = TokenManager.getInstance(appContext).getUserId()?.takeIf(String::isNotBlank) ?: return null
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacy(prefs, userId)
        return AccountPreferences(prefs, userId)
    }

    /** The first authenticated account after upgrade claims the former global settings. */
    private fun migrateLegacy(prefs: SharedPreferences, userId: String) {
        val marker = scopedKey(KEY_MIGRATED, userId)
        if (prefs.getBoolean(marker, false)) return
        synchronized(migrationLock) {
            if (prefs.getBoolean(marker, false)) return
            val editor = prefs.edit()
            BOOLEAN_KEYS.forEach { key ->
                val target = scopedKey(key, userId)
                if (!prefs.contains(target) && prefs.contains(key)) {
                    editor.putBoolean(target, prefs.getBoolean(key, true))
                }
                editor.remove(key)
            }
            INT_KEYS.forEach { key ->
                val target = scopedKey(key, userId)
                if (!prefs.contains(target) && prefs.contains(key)) {
                    editor.putInt(target, prefs.getInt(key, if (key == KEY_DND_START) 22 else 7))
                }
                editor.remove(key)
            }
            editor.putBoolean(marker, true).commit()
        }
    }

    private data class AccountPreferences(val prefs: SharedPreferences, val userId: String)
}
