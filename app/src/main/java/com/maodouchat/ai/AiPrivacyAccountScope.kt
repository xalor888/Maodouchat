package com.maodouchat.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.maodouchat.network.TokenManager

// 账号隔离簇：按 userId 隔离偏好 + 老版本设备级键的一次性迁移。
internal object AiPrivacyAccountScope {
    private val migrationLock = Any()

    internal fun account(context: Context): AccountPreferences? {
        val appContext = context.applicationContext
        val userId = TokenManager.getInstance(appContext).getUserId()
            ?.takeIf(String::isNotBlank) ?: return null
        val prefs = appContext.getSharedPreferences(AiPrivacyKeys.PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacy(prefs, userId)
        return AccountPreferences(prefs, userId)
    }

    internal data class AccountPreferences(val prefs: SharedPreferences, val userId: String)

    /** 升级后首次登录的账号继承原来的设备级同意状态。 */
    private fun migrateLegacy(prefs: SharedPreferences, userId: String) {
        val marker = AiPrivacyKeys.scopedKey(AiPrivacyKeys.MIGRATED, userId)
        if (prefs.getBoolean(marker, false)) return
        synchronized(migrationLock) {
            if (prefs.getBoolean(marker, false)) return
            prefs.edit {
                listOf(AiPrivacyKeys.CONSENT, AiPrivacyKeys.LOCAL_SAFETY).forEach { key ->
                    val target = AiPrivacyKeys.scopedKey(key, userId)
                    if (!prefs.contains(target) && prefs.contains(key)) {
                        putBoolean(target, prefs.getBoolean(key, false))
                    }
                    remove(key)
                }
                val dismissedTarget = AiPrivacyKeys.scopedKey(AiPrivacyKeys.DISMISSED_SAFETY_IDS, userId)
                if (!prefs.contains(dismissedTarget) && prefs.contains(AiPrivacyKeys.DISMISSED_SAFETY_IDS)) {
                    putStringSet(
                        dismissedTarget,
                        prefs.getStringSet(AiPrivacyKeys.DISMISSED_SAFETY_IDS, emptySet())?.toSet().orEmpty()
                    )
                }
                remove(AiPrivacyKeys.DISMISSED_SAFETY_IDS)
                // apply() 异步落盘但内存缓存同步更新，同进程后续读能立即看到；主线程不能用同步 commit()。
                putBoolean(marker, true)
            }
        }
    }
}
