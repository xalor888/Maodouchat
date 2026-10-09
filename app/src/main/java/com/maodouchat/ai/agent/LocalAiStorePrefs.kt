package com.maodouchat.ai.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.maodouchat.network.TokenManager
import com.maodouchat.security.AccountIsolationPolicy
import org.json.JSONObject

// 三簇共用的加密 prefs：账号隔离的 key 派生与 EncryptedSharedPreferences 打开逻辑。
internal object LocalAiStorePrefs {
    internal const val PREFS = "maodou_local_ai"
    internal const val KEY_PROVIDERS = "providers_json"
    internal const val KEY_ACTIVE = "active_provider_id"
    internal const val KEY_OVERLAY = "overlay_mode"
    internal const val KEY_SESSIONS = "sessions_json"

    internal fun scoped(context: Context, key: String): String {
        val userId = TokenManager.getInstance(context).getUserId().orEmpty()
        return AccountIsolationPolicy.preferenceKey(key, userId)
    }

    internal fun prefs(context: Context): SharedPreferences? {
        val userId = TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() } ?: return null
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            "$PREFS-$userId",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    internal fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { !it.isNaN() }
    }
}
