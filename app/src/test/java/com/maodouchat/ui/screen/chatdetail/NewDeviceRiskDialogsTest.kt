package com.maodouchat.ui.screen.chatdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.R
import com.maodouchat.util.SecretNewDeviceRiskPrefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G182b：用 G181b 解锁的 Robolectric 能力，补上最后两个 dialog 的 UI 覆盖。
 *
 * 在这之前（G178b 的记录）这两个 dialog 被判定为「无法覆盖」——它们的出现前提是
 * 真机未登记设备，instrumented 的 `createComposeRule` 渲染不出那个上下文。
 * Robolectric 下可以了。
 *
 * ⚠️ **关于 `SecretNewDeviceRiskPrefs.isDeviceTrusted` 的覆盖缺口（本轮实测确认）**：
 * 它本应是本次的重点，但跑不下来——`isEnabled` / `setEnabled` / `setKnownDevices`
 * 全都要经 `AccountFeatureSwitch.userId()` = `TokenManager.getUserId()`，
 * 而 `TokenManager` 用 `EncryptedSharedPreferences`（Android Keystore）。
 * Robolectric 下 Keystore 不可用：实测
 * `saveAuthSession(...) = false`、`getUserId() = null`、`isLoggedIn() = false`。
 * 于是 userId 永远为 null，`setEnabled`/`setKnownDevices` **静默失效**
 * （`AccountFeatureSwitch` 里都是 `val userId = userId(context) ?: return`）。
 *
 * 结果：只有 `deviceId.isBlank() -> false` 这一条分支不需要 userId、能真测；
 * 另外三条（开关关闭 / 已登记 / 未登记）在 Robolectric 下无法构造前提。
 * 这不算「测过了」，缺口照实记在这里。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NewDeviceRiskDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun ctx() = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().targetContext

    private fun str(id: Int) = ctx().getString(id)

    @Test
    fun newDeviceRiskPromptRoutesConfirmAndCancel() {
        var register = 0; var keepLocked = 0
        compose.setContent {
            NewDeviceRiskPromptDialog(onRegister = { register++ }, onKeepLocked = { keepLocked++ })
        }
        compose.onNodeWithText(str(R.string.secret_new_device_risk_prompt_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.secret_new_device_risk_prompt_body)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.common_confirm)).performClick()
        compose.runOnIdle { check(register == 1) { "点确定应触发 onRegister，实际 $register" } }
        check(keepLocked == 0) { "点确定不应触发 onKeepLocked" }

        compose.onNodeWithText(str(R.string.common_cancel)).performClick()
        compose.runOnIdle { check(keepLocked == 1) { "点取消应触发 onKeepLocked，实际 $keepLocked" } }
    }

    @Test
    fun newDeviceRiskLockedShowsCopyAndRoutesRegister() {
        var register = 0
        compose.setContent { SecretNewDeviceRiskLocked(onRegisterClick = { register++ }) }

        compose.onNodeWithText(str(R.string.secret_new_device_risk_locked)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.secret_new_device_risk_register)).performClick()
        compose.runOnIdle { check(register == 1) { "点登记应触发 onRegisterClick，实际 $register" } }
    }

    /**
     * `isDeviceTrusted` 里**唯一不依赖 userId** 的分支：空指纹永远不可信。
     * 这一条是安全相关的——空 `deviceId` 若被判为可信，未登记设备就绕过了风控。
     */
    @Test
    fun blankDeviceIdIsNeverTrusted() {
        val c = ctx()
        check(!SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "")) { "空 deviceId 不该被信任" }
        check(!SecretNewDeviceRiskPrefs.isDeviceTrusted(c, "   ")) { "纯空白 deviceId 不该被信任" }
    }
}
