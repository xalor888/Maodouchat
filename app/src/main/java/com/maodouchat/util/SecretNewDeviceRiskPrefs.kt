package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_new_device_risk")


    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_KNOWN_DEVICES = "known_devices"

    /** 已登记的设备指纹集合（见 [SimChangeWatcher]/设备核验的 deviceId 来源）。 */
    fun knownDevices(context: Context): Set<String> {
        val userId = switch.userId(context) ?: return emptySet()
        return switch.prefs(context).getStringSet(switch.key(KEY_KNOWN_DEVICES, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setKnownDevices(context: Context, devices: Set<String>) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit()
            .putStringSet(switch.key(KEY_KNOWN_DEVICES, userId), devices.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isDeviceTrusted(context: Context, deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        if (!isEnabled(context)) return true
        return deviceId in knownDevices(context)
    }

    fun registerDevice(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        val userId = switch.userId(context) ?: return
        val updated = knownDevices(context) + deviceId.trim()
        switch.prefs(context).edit()
            .putStringSet(switch.key(KEY_KNOWN_DEVICES, userId), updated)
            .apply()
    }
}
