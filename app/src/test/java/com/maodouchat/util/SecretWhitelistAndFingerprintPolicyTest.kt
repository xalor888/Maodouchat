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
 * G186b：最后两个 `Secret*Prefs` 安全决策的覆盖。核心差异钉住：转发白名单是**允许列表**
 * 语义——空集合 = 谁都不许转发（fail-closed），而不是谁都可以；这点最容易在重构中被抹掉。
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

    // ---- oct22bf 续批：五态 ----

    @Test
    fun fingerprintMatchingIsCaseSensitive() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)
        SecretDeviceVerifyPrefs.markFingerprintVerified(c, "FP-A")
        check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp-a")) {
            "指纹匹配必须大小写敏感"
        }
        check(SecretDeviceVerifyPrefs.isFingerprintVerified(c, "FP-A")) { "原样应命中" }
    }

    @Test
    fun markFingerprintVerifiedIgnoresBlank() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)
        SecretDeviceVerifyPrefs.markFingerprintVerified(c, "   ")
        SecretDeviceVerifyPrefs.markFingerprintVerified(c, "")
        check(SecretDeviceVerifyPrefs.verifiedFingerprints(c).isEmpty()) {
            "空白指纹写入应被忽略"
        }
    }

    @Test
    fun fingerprintSetIsIsolatedPerAccount() {
        val c = ctx
        SecretDeviceVerifyPrefs.setEnabled(c, true)
        SecretDeviceVerifyPrefs.markFingerprintVerified(c, "fp-a")
        AccountFeatureSwitch.userIdOverrideForTest = { "other-user" }
        try {
            check(!SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp-a")) {
                "换账号后他账号读不到本账号指纹"
            }
            check(SecretDeviceVerifyPrefs.verifiedFingerprints(c).isEmpty()) { "他账号集合应为空" }
        } finally {
            AccountFeatureSwitch.userIdOverrideForTest = { "user-under-test" }
        }
        check(SecretDeviceVerifyPrefs.isFingerprintVerified(c, "fp-a")) { "切回原账号仍可信" }
    }

    @Test
    fun whitelistReadTrimsRawStoredValues() {
        val c = ctx
        SecretForwardWhitelistPrefs.setEnabled(c, true)
        // 绕过 setter 写裸集合：getter 读回同样裁边剔空白
        val prefs = c.getSharedPreferences("secret_forward_whitelist", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("whitelist:user-under-test", setOf(" user-a ", "  ", "")).apply()
        check(SecretForwardWhitelistPrefs.whitelist(c) == setOf("user-a")) {
            "getter 必须裁边并剔空白，实际 ${SecretForwardWhitelistPrefs.whitelist(c)}"
        }
    }

    @Test
    fun forwardDecisionRepeatsDeterministically() {
        val c = ctx
        SecretForwardWhitelistPrefs.setEnabled(c, true)
        SecretForwardWhitelistPrefs.setWhitelist(c, setOf("user-a"))
        repeat(3) {
            check(SecretForwardWhitelistPrefs.isForwardAllowed(c, "user-a")) { "命中判定应稳定放行" }
            check(!SecretForwardWhitelistPrefs.isForwardAllowed(c, "user-b")) { "未命中判定应稳定禁止" }
        }
    }
}
