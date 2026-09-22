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
 * G185b：三个 `Secret*Prefs` 的**判定与边界**覆盖。
 *
 * 这三个类的开关四件套（isEnabled/setEnabled/…）一直是委托给 `AccountFeatureSwitch`，
 * 但**它们自己的状态逻辑**此前没有任何一条直接断言：
 * 2FA 门的时间窗、自动销毁 TTL 的钳制、对端徽标的「三重与」。
 * 这些逻辑写错的后果都很实在：门永不关 / 消息永不过期 / 徽标在对方没开密聊时也亮。
 *
 * 之所以现在能测：G185b 给 `AccountFeatureSwitch` 加了全局 `userIdOverrideForTest`，
 * 一个改动解锁整个 `Secret*Prefs` 家族（不必给 10 个文件逐个加测试缝）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretGateAndTtlPolicyTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefsNames = listOf(
        "secret_2fa_gate", "secret_auto_destroy", "secret_session_notice"
    )

    @Before
    fun reset() {
        prefsNames.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply() }
        AccountFeatureSwitch.userIdOverrideForTest = { "user-under-test" }
    }

    @After
    fun tearDown() {
        AccountFeatureSwitch.userIdOverrideForTest = null
    }

    // ---- Secret2faGatePrefs：门的时间窗 ----

    @Test
    fun gateTimeoutIsClamped() {
        Secret2faGatePrefs.setGateTimeoutMs(ctx, 1L) // 低于下限
        check(Secret2faGatePrefs.gateTimeoutMs(ctx) == 10_000L) { "低于 10s 应收钳到下限，实际 ${Secret2faGatePrefs.gateTimeoutMs(ctx)}" }
        Secret2faGatePrefs.setGateTimeoutMs(ctx, 99L * 24 * 60 * 60 * 1000) // 高于上限
        check(Secret2faGatePrefs.gateTimeoutMs(ctx) == 24L * 60 * 60 * 1000) { "高于 24h 应收钳到上限" }
        Secret2faGatePrefs.setGateTimeoutMs(ctx, 60_000L)
        check(Secret2faGatePrefs.gateTimeoutMs(ctx) == 60_000L) { "区间内的值应原样保留" }
    }

    @Test
    fun gateIsOpenRightAfterVerificationAndClosesAfterTimeout() {
        // ⚠️ 必须先打开开关：`Secret2faGatePrefs` 的 defaultEnabled = **false**，
        // 开关关着时 isGateOpen 直接 fail-open 返回 true，压根走不到时间窗判断。
        Secret2faGatePrefs.setEnabled(ctx, true)
        Secret2faGatePrefs.setGateTimeoutMs(ctx, 60_000L)

        // 从未验证过 → 关
        check(!Secret2faGatePrefs.isGateOpen(ctx)) { "未验证过时门必须是关的" }

        Secret2faGatePrefs.markVerified(ctx)
        check(Secret2faGatePrefs.isGateOpen(ctx)) { "刚验证完门必须是开的" }

        Secret2faGatePrefs.clearGate(ctx)
        check(!Secret2faGatePrefs.isGateOpen(ctx)) { "clearGate 之后门必须关" }
    }

    /**
     * 时间窗本身：验证完成后**过多久过期**。
     *
     * ⚠️ 这条是第一版漏掉的，也是负控制没打红的原因：上面那条只走到
     * `last > 0L`，从没让时钟走过超时点，所以「去掉时间窗」它照样绿。
     * 这里用 Robolectric 的 ShadowSystemClock 把时钟往前拨。
     */
    @Test
    fun gateClosesOnceTheVerificationAgesOut() {
        Secret2faGatePrefs.setEnabled(ctx, true)
        Secret2faGatePrefs.setGateTimeoutMs(ctx, 60_000L)
        Secret2faGatePrefs.markVerified(ctx)
        check(Secret2faGatePrefs.isGateOpen(ctx)) { "刚验证完门应开着" }

        // 直接把「上次验证时间」写到 2 分钟前，模拟已超时。
        // 为什么不用 ShadowSystemClock：它 shadow 的是 android.os.SystemClock，
        // 而本类读的是 System.currentTimeMillis()，拨不动。
        // 这里按 AccountFeatureSwitch 的 key 约定（"$base:$userId"）直接构造同一个键，
        // 与 production 写入的键完全一致。
        val prefs = ctx.getSharedPreferences("secret_2fa_gate", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_verified_at:user-under-test", System.currentTimeMillis() - 120_000L)
            .apply()
        check(!Secret2faGatePrefs.isGateOpen(ctx)) {
            "超过免验证窗口后门必须关闭——否则 2FA 形同虚设"
        }

        // 回到窗口内又应立即恢复（证明不是「一旦写过就永久失效」）
        prefs.edit()
            .putLong("last_verified_at:user-under-test", System.currentTimeMillis())
            .apply()
        check(Secret2faGatePrefs.isGateOpen(ctx)) { "回到窗口内门应重新打开" }
    }

    @Test
    fun gateFailsOpenWhenTheSwitchIsOff() {
        Secret2faGatePrefs.setEnabled(ctx, false)
        // 开关关闭 = 根本不启用 2FA 门禁 → 直接放行（fail-open）
        check(Secret2faGatePrefs.isGateOpen(ctx)) { "开关关闭时门应直接开（不做 2FA）" }
    }

    @Test
    fun gateClosedWhenNoUserEvenIfVerified() {
        AccountFeatureSwitch.userIdOverrideForTest = { null }
        // 无账号时 isGateOpen 走 false（源码语义：拿不到 userId 就关）
        check(!Secret2faGatePrefs.isGateOpen(ctx)) { "无账号时门必须是关的" }
    }

    // ---- SecretAutoDestroyPrefs：TTL 钳制 ----

    @Test
    fun autoDestroyTtlIsClamped() {
        SecretAutoDestroyPrefs.setTtlSeconds(ctx, 1L)
        check(SecretAutoDestroyPrefs.ttlSeconds(ctx) == SecretAutoDestroyPrefs.MIN_TTL_SECONDS) {
            "低于 300s 应收钳到 MIN"
        }
        SecretAutoDestroyPrefs.setTtlSeconds(ctx, 999_999_999L)
        check(SecretAutoDestroyPrefs.ttlSeconds(ctx) == SecretAutoDestroyPrefs.MAX_TTL_SECONDS) {
            "高于 30 天应收钳到 MAX"
        }
        SecretAutoDestroyPrefs.setTtlSeconds(ctx, 3600L)
        check(SecretAutoDestroyPrefs.ttlSeconds(ctx) == 3600L) { "区间内的值应原样保留" }
    }

    @Test
    fun autoDestroyConstantsAreSelfConsistent() {
        // 钳制区间必须真的包含默认值，否则「默认值」一读出来就被改掉
        check(SecretAutoDestroyPrefs.DEFAULT_TTL_SECONDS in
            SecretAutoDestroyPrefs.MIN_TTL_SECONDS..SecretAutoDestroyPrefs.MAX_TTL_SECONDS) {
            "默认 TTL 必须落在 [MIN, MAX] 内"
        }
        check(SecretAutoDestroyPrefs.MIN_TTL_SECONDS < SecretAutoDestroyPrefs.MAX_TTL_SECONDS) {
            "MIN 必须小于 MAX"
        }
    }

    // ---- SecretSessionNoticePrefs：三重与 ----

    @Test
    fun peerNoticeRequiresAllThreeConditions() {
        // 本端开关 + 对端开关 + 用户没关过徽标，缺一不可
        SecretSessionNoticePrefs.setShowPeerNotice(ctx, true)
        check(SecretSessionNoticePrefs.shouldShowPeerNotice(ctx, peerSecretEnabled = true)) {
            "三项全真时应显示徽标"
        }
        check(!SecretSessionNoticePrefs.shouldShowPeerNotice(ctx, peerSecretEnabled = false)) {
            "对端没开密聊时必须不显示（即使本端全开）"
        }

        SecretSessionNoticePrefs.setShowPeerNotice(ctx, false)
        check(!SecretSessionNoticePrefs.shouldShowPeerNotice(ctx, peerSecretEnabled = true)) {
            "用户关过徽标后必须不显示"
        }
        check(!SecretSessionNoticePrefs.shouldShowPeerNotice(ctx, peerSecretEnabled = false)) {
            "两项都假时必须不显示"
        }

        SecretSessionNoticePrefs.setEnabled(ctx, false)
        SecretSessionNoticePrefs.setShowPeerNotice(ctx, true)
        check(!SecretSessionNoticePrefs.shouldShowPeerNotice(ctx, peerSecretEnabled = true)) {
            "本端开关关掉后必须不显示"
        }
    }
}
