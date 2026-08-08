package com.maodouchat.ai

import android.content.Context
import com.maodouchat.network.TokenManager
import com.maodouchat.security.AccountIsolationPolicy

/**
 * 账号隔离的写作风格偏好存储。默认全关；换号不串数据。
 */
object AiWritingStylePreferences {
    private const val PREFS = "ai_writing_style"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_CUSTOM = "custom_note"

    fun snapshot(context: Context): AiWritingStylePolicy.Snapshot {
        val account = account(context) ?: return AiWritingStylePolicy.clear()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AiWritingStylePolicy.normalize(
            enabled = prefs.getBoolean(key(KEY_ENABLED, account), false),
            presetId = prefs.getString(key(KEY_PRESET, account), null),
            customNote = prefs.getString(key(KEY_CUSTOM, account), null)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val account = account(context) ?: return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!enabled) {
            clear(context)
            return
        }
        prefs.edit().putBoolean(key(KEY_ENABLED, account), true).apply()
    }

    fun setPreset(context: Context, presetId: String) {
        val account = account(context) ?: return
        val preset = AiWritingStylePolicy.Preset.fromId(presetId)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(KEY_PRESET, account), preset.id)
            .apply()
    }

    fun setCustomNote(context: Context, note: String) {
        val account = account(context) ?: return
        val normalized = AiWritingStylePolicy.normalizeCustomNote(note)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(KEY_CUSTOM, account), normalized)
            .apply()
    }

    fun save(context: Context, enabled: Boolean, presetId: String?, customNote: String?) {
        val account = account(context) ?: return
        val snap = AiWritingStylePolicy.normalize(enabled, presetId, customNote)
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val eKey = key(KEY_ENABLED, account)
        val pKey = key(KEY_PRESET, account)
        val cKey = key(KEY_CUSTOM, account)
        if (!snap.enabled) {
            editor.remove(eKey).remove(pKey).remove(cKey)
        } else {
            editor.putBoolean(eKey, true)
                .putString(pKey, snap.preset.id)
                .putString(cKey, snap.customNote)
        }
        editor.apply()
    }

    fun clear(context: Context) {
        val account = account(context) ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(KEY_ENABLED, account))
            .remove(key(KEY_PRESET, account))
            .remove(key(KEY_CUSTOM, account))
            .apply()
    }

    private fun key(base: String, userId: String): String =
        AccountIsolationPolicy.preferenceKey(base, userId)

    private fun account(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf(String::isNotBlank)
}
