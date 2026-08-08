package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊设备核验开关（B2 surface · 设备核验，health 名 dvz）。
 *
 * 开启后，进入密聊会话前展示对端设备指纹核验页（与既有安全码/盲水印指纹同源），
 * 已核验的指纹记入 [KEY_VERIFIED_FINGERPRINTS]；指纹不一致时拒绝展示密聊内容并提示。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretDeviceVerifyPrefs {
    private const val PREFS = "secret_device_verify"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_VERIFIED_FINGERPRINTS = "verified_fingerprints"

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putBoolean(key(KEY_ENABLED, userId), enabled)
            .putBoolean(key(KEY_USER_SET, userId), true)
            .apply()
    }

    /** 用户是否显式设置过该开关（设置页写入）；未设置时接受服务端默认值。 */
    fun isUserSet(context: Context): Boolean {
        val userId = userId(context) ?: return false
        return prefs(context).contains(key(KEY_USER_SET, userId))
    }

    /** 服务端下发默认值：仅当用户从未显式设置过时生效（本地开关优先）。 */
    fun applyServerDefault(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        if (isUserSet(context)) return
        prefs(context).edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply()
    }

    /** 已核验的对端设备指纹集合（fingerprint -> chatId 映射由接入方按需扩展）。 */
    fun verifiedFingerprints(context: Context): Set<String> {
        val userId = userId(context) ?: return emptySet()
        return prefs(context).getStringSet(key(KEY_VERIFIED_FINGERPRINTS, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setVerifiedFingerprints(context: Context, fingerprints: Set<String>) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putStringSet(key(KEY_VERIFIED_FINGERPRINTS, userId), fingerprints.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isFingerprintVerified(context: Context, fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false
        if (!isEnabled(context)) return true
        return fingerprint in verifiedFingerprints(context)
    }

    fun markFingerprintVerified(context: Context, fingerprint: String) {
        if (fingerprint.isBlank()) return
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putStringSet(key(KEY_VERIFIED_FINGERPRINTS, userId), verifiedFingerprints(context) + fingerprint.trim())
            .apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
