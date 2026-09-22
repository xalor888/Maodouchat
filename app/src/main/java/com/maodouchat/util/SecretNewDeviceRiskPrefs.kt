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

    /**
     * 仅供测试替换 [switch]，以便注入 userId 来源（G183b）。
     *
     * 生产路径永远是上面的默认构造（userId 来自 TokenManager）；
     * 只有 JVM 测试需要它——Robolectric 下 Keystore 不可用，默认来源拿不到 userId。
     */
    internal var switchOverrideForTest: AccountFeatureSwitch? = null
    private val activeSwitch: AccountFeatureSwitch get() = switchOverrideForTest ?: switch

    fun isEnabled(context: Context): Boolean = activeSwitch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = activeSwitch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = activeSwitch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = activeSwitch.applyServerDefault(context, enabled)
    private const val KEY_KNOWN_DEVICES = "known_devices"

    /** 已登记的设备指纹集合（见 [SimChangeWatcher]/设备核验的 deviceId 来源）。 */
    fun knownDevices(context: Context): Set<String> {
        val userId = activeSwitch.userId(context) ?: return emptySet()
        return activeSwitch.prefs(context).getStringSet(activeSwitch.key(KEY_KNOWN_DEVICES, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setKnownDevices(context: Context, devices: Set<String>) {
        val userId = activeSwitch.userId(context) ?: return
        activeSwitch.prefs(context).edit()
            .putStringSet(activeSwitch.key(KEY_KNOWN_DEVICES, userId), devices.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isDeviceTrusted(context: Context, deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        if (!isEnabled(context)) return true
        return deviceId in knownDevices(context)
    }

    fun registerDevice(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        val userId = activeSwitch.userId(context) ?: return
        val updated = knownDevices(context) + deviceId.trim()
        activeSwitch.prefs(context).edit()
            .putStringSet(activeSwitch.key(KEY_KNOWN_DEVICES, userId), updated)
            .apply()
    }
}
