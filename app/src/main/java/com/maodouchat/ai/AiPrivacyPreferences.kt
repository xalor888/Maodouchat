package com.maodouchat.ai

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.TokenManager

/** Account-scoped local AI consent and on-device safety preferences. */
object AiPrivacyPreferences {
    private const val PREFS_NAME = "ai_settings"
    const val KEY_CONSENT = "ai_consent_accepted"
    const val KEY_LOCAL_SAFETY = "ai_local_safety_enabled"
    const val KEY_DISMISSED_SAFETY_IDS = "ai_local_safety_dismissed_ids"
    private const val KEY_MIGRATED = "account_scope_migrated"
    private val migrationLock = Any()

    internal fun scopedKey(base: String, userId: String): String = "$base:$userId"

    fun consentAccepted(context: Context): Boolean =
        account(context)?.let { it.prefs.getBoolean(scopedKey(KEY_CONSENT, it.userId), false) } ?: false

    fun localSafetyEnabled(context: Context): Boolean =
        account(context)?.let { it.prefs.getBoolean(scopedKey(KEY_LOCAL_SAFETY, it.userId), false) } ?: false

    fun dismissedSafetyMessageIds(context: Context): Set<String> =
        account(context)?.let {
            it.prefs.getStringSet(scopedKey(KEY_DISMISSED_SAFETY_IDS, it.userId), emptySet())?.toSet().orEmpty()
        }.orEmpty()

    fun setConsentAccepted(context: Context, accepted: Boolean) =
        putBoolean(context, KEY_CONSENT, accepted)

    fun setLocalSafetyEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, KEY_LOCAL_SAFETY, enabled)

    fun setDismissedSafetyMessageIds(context: Context, messageIds: Set<String>) {
        val account = account(context) ?: return
        account.prefs.edit()
            .putStringSet(scopedKey(KEY_DISMISSED_SAFETY_IDS, account.userId), messageIds.toSet())
            .apply()
    }

    fun revoke(context: Context) {
        val account = account(context) ?: return
        account.prefs.edit()
            .putBoolean(scopedKey(KEY_CONSENT, account.userId), false)
            .putBoolean(scopedKey(KEY_LOCAL_SAFETY, account.userId), false)
            .remove(scopedKey(KEY_DISMISSED_SAFETY_IDS, account.userId))
            .apply()
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        val account = account(context) ?: return
        account.prefs.edit().putBoolean(scopedKey(key, account.userId), value).apply()
    }

    private fun account(context: Context): AccountPreferences? {
        val appContext = context.applicationContext
        val userId = TokenManager.getInstance(appContext).getUserId()?.takeIf(String::isNotBlank) ?: return null
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacy(prefs, userId)
        return AccountPreferences(prefs, userId)
    }

    /** The first authenticated account after upgrade claims the former device-global consent. */
    private fun migrateLegacy(prefs: SharedPreferences, userId: String) {
        val marker = scopedKey(KEY_MIGRATED, userId)
        if (prefs.getBoolean(marker, false)) return
        synchronized(migrationLock) {
            if (prefs.getBoolean(marker, false)) return
            val editor = prefs.edit()
            listOf(KEY_CONSENT, KEY_LOCAL_SAFETY).forEach { key ->
                val target = scopedKey(key, userId)
                if (!prefs.contains(target) && prefs.contains(key)) {
                    editor.putBoolean(target, prefs.getBoolean(key, false))
                }
                editor.remove(key)
            }
            val dismissedTarget = scopedKey(KEY_DISMISSED_SAFETY_IDS, userId)
            if (!prefs.contains(dismissedTarget) && prefs.contains(KEY_DISMISSED_SAFETY_IDS)) {
                editor.putStringSet(
                    dismissedTarget,
                    prefs.getStringSet(KEY_DISMISSED_SAFETY_IDS, emptySet())?.toSet().orEmpty()
                )
            }
            editor.remove(KEY_DISMISSED_SAFETY_IDS)
            // apply() 异步落盘但同步更新内存缓存，同进程后续 account() 立即读到新值；
            // 与同文件其他迁移路径一致，避免在主/UI 线程做同步 commit() 导致 jank/ANR。
            editor.putBoolean(marker, true).apply()
        }
    }

    private data class AccountPreferences(val prefs: SharedPreferences, val userId: String)
}
