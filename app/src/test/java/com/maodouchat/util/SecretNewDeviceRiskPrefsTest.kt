package com.maodouchat.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G183b：`SecretNewDeviceRiskPrefs.isDeviceTrusted` 的**四条分支全覆盖**。
 *
 * 这是 B2 新设备风控的核心安全决策：未登记设备必须判为不可信，
 * 否则密聊会在新设备上直接展示内容。
 *
 * 为什么现在才能测：`AccountFeatureSwitch` 原来硬编码走
 * `TokenManager.getUserId()`，而 TokenManager 用 `EncryptedSharedPreferences`
 * （Android Keystore），Robolectric 下不可用（G182b 实测 `getUserId()=null`），
 * 于是 userId 永远为 null、`setEnabled`/`setKnownDevices` 静默失效。
 * G183b 给 `AccountFeatureSwitch` 加了可注入的 `userIdProvider`，
 * 这里用假来源造出「已登录 user-under-test」。
 *
 * 注意：这里**不需要** mock `SecretNewDeviceRiskPrefs`——SharedPreferences 在
 * Robolectric 下是真的，`setEnabled`/`setKnownDevices` 真实落盘。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretNewDeviceRiskPrefsTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val userId = "user-under-test"

    /**
     * 每个用例前重建：换干净 prefs + 固定 userId 来源，
     * 避免同一进程内用例互相污染（object 单例，prefs 名相同）。
     */
    @Before
    fun reset() {
        ctx.getSharedPreferences("secret_new_device_risk", Context.MODE_PRIVATE).edit().clear().apply()
        // G187b：改用 AccountFeatureSwitch 的全局覆盖（G185b 加的），
        // 不再需要每个 Secret*Prefs 自己带测试缝。
        AccountFeatureSwitch.userIdOverrideForTest = { userId }
    }

    /**
     * 必须复位全局覆盖：Robolectric 的多个测试类共用同一个 JVM，
     * 不复位会让后面运行的测试拿到一个假 userId（它们本应有 null = 未登录）。
     */
    @After
    fun tearDown() {
        AccountFeatureSwitch.userIdOverrideForTest = null
    }

    @Test
    fun switchOffTrustsEveryDevice() {
        val c = ctx
        SecretNewDeviceRiskPrefs.setEnabled(c, false)
        // 开关关闭 = 根本不做设备风控
        check(SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "device-a")) { "开关关闭时应信任 device-a" }
        check(SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "never-seen")) { "开关关闭时应信任任意设备" }
        // 但空指纹仍然不可信（这条独立于开关）
        check(!SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "")) { "空 deviceId 永不可信" }
    }

    @Test
    fun blankDeviceIdIsNeverTrusted() {
        val c = ctx
        SecretNewDeviceRiskPrefs.setEnabled(c, true)
        SecretNewDeviceRiskPrefs.setKnownDevices(c, setOf("device-a"))
        check(!SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "")) { "空 deviceId 不该被信任" }
        check(!SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "   ")) { "纯空白 deviceId 不该被信任" }
    }

    @Test
    fun registeredDeviceIsTrusted() {
        val c = ctx
        SecretNewDeviceRiskPrefs.setEnabled(c, true)
        SecretNewDeviceRiskPrefs.setKnownDevices(c, setOf("device-a", "device-b"))
        check(SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "device-a")) { "已登记设备 device-a 应被信任" }
        check(SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "device-b")) { "已登记设备 device-b 应被信任" }
        // 登记后 knownDevices 真的读到了两个
        check(SecretNewDeviceRiskPrefs.knownDevices(c) == setOf("device-a", "device-b")) {
            "knownDevices 应回读到刚登记的两台，实际 ${SecretNewDeviceRiskPrefs.knownDevices(c)}"
        }
    }

    @Test
    fun unregisteredDeviceIsNotTrusted() {
        val c = ctx
        SecretNewDeviceRiskPrefs.setEnabled(c, true)
        SecretNewDeviceRiskPrefs.setKnownDevices(c, setOf("device-a"))
        // 整条 B2 风控的落点：未登记 = 高风险 = 密聊锁定
        check(!SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "device-c")) {
            "未登记设备必须判为不可信——否则密聊会在新设备上直接展示内容"
        }
    }
}
