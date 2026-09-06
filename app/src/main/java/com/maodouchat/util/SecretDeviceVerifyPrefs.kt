package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_device_verify")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_VERIFIED_FINGERPRINTS = "verified_fingerprints"

    /** 已核验的对端设备指纹集合（fingerprint -> chatId 映射由接入方按需扩展）。 */
    fun verifiedFingerprints(context: Context): Set<String> {
        val userId = switch.userId(context) ?: return emptySet()
        return switch.prefs(context).getStringSet(switch.key(KEY_VERIFIED_FINGERPRINTS, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setVerifiedFingerprints(context: Context, fingerprints: Set<String>) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit()
            .putStringSet(switch.key(KEY_VERIFIED_FINGERPRINTS, userId), fingerprints.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isFingerprintVerified(context: Context, fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false
        if (!isEnabled(context)) return true
        return fingerprint in verifiedFingerprints(context)
    }

    fun markFingerprintVerified(context: Context, fingerprint: String) {
        if (fingerprint.isBlank()) return
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit()
            .putStringSet(switch.key(KEY_VERIFIED_FINGERPRINTS, userId), verifiedFingerprints(context) + fingerprint.trim())
            .apply()
    }
}
