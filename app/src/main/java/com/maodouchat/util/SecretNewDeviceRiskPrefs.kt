package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊新设备风控开关（B2 surface · 新设备风控，health 名 ndz）。
 *
 * 开启后，本机只信任 [knownDeviceIds] 中登记过的设备指纹；
 * 首次在本设备（或新安装）进入密聊时，需要先登记设备指纹，
 * 未登记设备视为高风险：密聊会话默认锁定且不展示内容预览。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretNewDeviceRiskPrefs {
    private const val PREFS = "secret_new_device_risk"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_KNOWN_DEVICES = "known_devices"

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

    /** 已登记的设备指纹集合（见 [SimChangeWatcher]/设备核验的 deviceId 来源）。 */
    fun knownDevices(context: Context): Set<String> {
        val userId = userId(context) ?: return emptySet()
        return prefs(context).getStringSet(key(KEY_KNOWN_DEVICES, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setKnownDevices(context: Context, devices: Set<String>) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putStringSet(key(KEY_KNOWN_DEVICES, userId), devices.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isDeviceTrusted(context: Context, deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        if (!isEnabled(context)) return true
        return deviceId in knownDevices(context)
    }

    fun registerDevice(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        val userId = userId(context) ?: return
        val updated = knownDevices(context) + deviceId.trim()
        prefs(context).edit()
            .putStringSet(key(KEY_KNOWN_DEVICES, userId), updated)
            .apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
