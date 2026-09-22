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
 * G186b：最后两个 `Secret*Prefs` 安全决策的覆盖。
 *
 * 之所以单独立项：这两个函数和已经覆盖的 `SecretNewDeviceRiskPrefs.isDeviceTrusted`
 * **fail 方向不一样**，而这个差异正是容易被「统一重构」抹掉的东西：
 *
 * | 函数 | 开关关闭时 | 状态集合为空时 |
 * |---|---|---|
 * | `isDeviceTrusted` / `isFingerprintVerified` | **放行**（不做风控） | 不可信 |
 * | `isForwardAllowed` | **放行**（不做限制） | **一律禁止**（空白名单 = deny all） |
 *
 * 第三个格子是重点：转发白名单是**允许列表**语义，空集合必须解释为
 * 「谁都不许转发」，而不是「谁都可以转发」。写反的后果是密聊内容可以转发到
 * 未授信目标——这是安全性质。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretWhitelistAndFingerprintPolicyTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefsNames = listOf("secret_device_verify", "secret_forward_whitelist")

    @Before
    fun reset() {
        prefsNames.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply() }
        AccountFeatureSwitch.userIdOverrideForTest = { "user-under-test" }
    }

    @After
    fun tearDown() {
        AccountFeatureSwitch.userIdOverrideForTest = null
    }

    // ---- SecretDeviceVerifyPrefs：设备指纹核验 ----

    @Test
    fun fingerprintVerificationFollowsTheTrustedDeviceShape() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)

        // 空指纹永不可信（与 isDeviceTrusted 同一条安全底线）
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "")) { "空 fingerprint 不可信" }
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "   ")) { "纯空白 fingerprint 不可信" }

        // 未登记 → 不可信
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp-a")) { "未核验的 fingerprint 不可信" }

        // 登记后 → 可信
        SecretDeviceVerifyPrefs.markFingerprintVerified(c, "fp-a")
        check(SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp-a")) { "核验过的 fingerprint 应可信" }
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp-b")) { "没核验过的仍不可信" }

        // 开关关闭 → 放行（不做设备核验）
        SecretDeviceVerifyPrefs.setEnabled(c, false)
        check(SecretDeviceVerifyPrefs.isFingerprintVerified(c, "never-seen")) { "开关关闭时应放行任意 fingerprint" }
        // 但空指纹仍不可信（这条独立于开关）
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "")) { "开关关闭时空 fingerprint 仍不可信" }
    }

    @Test
    fun verifiedFingerprintsTrimAndDropBlanksOnWriteAndRead() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)
        // 写入带空白/空串的集合
        SecretDeviceVerifyPrefs.setVerifiedFingerprints(c, setOf(" fp-a ", "", "   ", "fp-b"))
        val stored = SecretDeviceVerifyPrefs.verifiedFingerprints(c)
        check(stored == setOf("fp-a", "fp-b")) { "应 trim 并剔掉空白项，实际 $stored" }
    }

    @Test
    fun fingerprintRoundTripsThroughRealPrefs() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)
        SecretDeviceVerifyPrefs.setVerifiedFingerprints(c, setOf("fp-x", "fp-y"))
        check(SecretDeviceVerifyPrefs.verifiedFingerprints(c) == setOf("fp-x", "fp-y")) {
            "set 之后必须能原样读回（防止只写不读/键写错）"
        }
    }

    // ---- SecretForwardWhitelistPrefs：允许列表（fail-closed）----

    @Test
    fun emptyWhitelistDeniesEverything() {
        val c = ctx
        SecretForwardWhitelistPrefs.setEnabled(c, true)
        // 这是本文件的核心：空白名单 = 一律禁止，不是「谁都可以」
        check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "any-target")) {
            "空白名单必须禁止一切转发——写反了密聊内容就能转发到未授信目标"
        }
        check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "")) { "空 targetId 也必须禁止" }
    }

    @Test
    fun whitelistHitAllowsAndMissDenies() {
        val c = ctx
        SecretForwardWhitelistPrefs.setEnabled(c, true)
        SecretForwardWhitelistPrefs.setWhitelist(c, setOf("user-a", "user-b"))
        check(SecretForwardWhitelistPrefs.isForwardAllowed(c, "user-a")) { "命中白名单应放行" }
        check(SecretForwardWhitelistPrefs.isForwardAllowed(c, "user-b")) { "命中白名单应放行" }
        check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "user-c")) { "未命中必须禁止" }
        check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "")) { "空 id 即使白名单非空也禁止" }
    }

    @Test
    fun whitelistTrimsAndDropsBlanks() {
        val c = ctx
        SecretForwardWhitelistPrefs.setEnabled(c, true)
        SecretForwardWhitelistPrefs.setWhitelist(c, setOf(" user-a ", "", "  "))
        val stored = SecretForwardWhitelistPrefs.whitelist(c)
        check(stored == setOf("user-a")) { "应 trim 并剔空白，实际 $stored" }
    }

    @Test
    fun switchOffBypassesTheWhitelist() {
        val c = ctx
        SecretForwardWhitelistPrefs.setEnabled(c, false)
        // 开关关闭 = 不启用转发限制 → 放行（与设备核验同一侧 fail-open）
        check(SecretForwardWhitelistPrefs.isForwardAllowed(c, "anyone")) { "开关关闭时应放行" }
        check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "")) { "但空 id 仍然禁止" }
    }

    /**
     * 把上表第三格那个「差异」钉成一条断言：
     * 空集合在两个世界里含义不同。
     */
    @Test
    fun emptySetMeansOppositeThingsForVerifyAndForward() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)
        SecretForwardWhitelistPrefs.setEnabled(c, true)
        // 设备核验：集合空 → 不可信（拒绝）
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp")) { "设备核验空集合 → 拒绝" }
        // 转发白名单：集合空 → 也拒绝，但**理由不同**（允许列表）
        check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "user")) { "转发白名单空集合 → 拒绝" }
        // 真正容易搞混的是开关关闭那一格：两者都放行
        SecretDeviceVerifyPrefs.setEnabled(c, false)
        SecretForwardWhitelistPrefs.setEnabled(c, false)
        check(SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp")) { "开关关闭 → 设备核验放行" }
        check(SecretForwardWhitelistPrefs.isForwardAllowed(c, "user")) { "开关关闭 → 转发放行" }
    }
}
