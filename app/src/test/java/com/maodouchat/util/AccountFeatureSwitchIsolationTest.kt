package com.maodouchat.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G184b：`AccountFeatureSwitch` 的**账号隔离**覆盖。
 *
 * 这是整个 `Secret*Prefs` 家族（10 个文件）的公共地基，KDoc 明写
 * 「账号隔离，默认开；仅本机生效」。但在本测试出现前，
 * 全仓库没有任何一处验证过隔离性。
 *
 * 为什么值得专门测：key 格式是 `"$base:$userId"`。哪天有人「简化」成 `base`
 * （去掉 `:$userId`），账号 A 的开关/设备指纹/白名单就会**泄漏到账号 B**。
 * 在 E2EE 项目里这是安全性质，不是普通功能 bug——而它编译通过、肉眼审 diff 也极易漏看。
 *
 * 这里直接测 `AccountFeatureSwitch` 本身（不经由某个 `Secret*Prefs`），
 * 用真的 SharedPreferences + 注入的 `userIdProvider`，不 mock。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccountFeatureSwitchIsolationTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "test_account_switch"

    /** 当前测试看到的账号；由每个用例设定。 */
    private var currentUser: String? = "user-a"

    private val switch = AccountFeatureSwitch(
        prefsName = prefsName,
        defaultEnabled = true,
        userIdProvider = { currentUser },
    )

    @Before
    fun reset() {
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
        currentUser = "user-a"
    }

    @Test
    fun enablingForOneAccountDoesNotAffectAnother() {
        currentUser = "user-a"
        switch.setEnabled(ctx, false)
        currentUser = "user-b"
        // user-b 从未设置过 → 回落 defaultEnabled(true)
        check(switch.isEnabled(ctx)) { "user-a 关掉开关不应影响 user-b（B 仍是默认开）" }

        currentUser = "user-a"
        check(!switch.isEnabled(ctx)) { "user-a 的设置应保持关闭" }
    }

    @Test
    fun isUserSetOnlyReflectsTheCurrentAccount() {
        currentUser = "user-a"
        check(!switch.isUserSet(ctx)) { "初始时任何账号都算未设置过" }
        switch.setEnabled(ctx, false)
        check(switch.isUserSet(ctx)) { "user-a 设置过之后应报 true" }

        currentUser = "user-b"
        check(!switch.isUserSet(ctx)) { "user-b 没设置过，不该被 user-a 的设置污染" }
    }

    @Test
    fun serverDefaultOnlyAppliesWhenTheUserNeverSetItAndDoesNotCrossAccounts() {
        currentUser = "user-a"
        switch.setEnabled(ctx, false) // 用户显式设置过 → 服务端默认不得覆盖
        switch.applyServerDefault(ctx, true)
        check(!switch.isEnabled(ctx)) { "用户显式设置过，服务端默认值不得覆盖本地选择" }

        // user-b 从未设置 → 服务端默认生效
        currentUser = "user-b"
        switch.applyServerDefault(ctx, false)
        check(!switch.isEnabled(ctx)) { "user-b 未设置过，应接受服务端默认值" }

        // 且不应反过来影响 user-a
        currentUser = "user-a"
        check(!switch.isEnabled(ctx)) { "user-a 仍是自己的显式设置（false）" }
    }

    @Test
    fun keysArePerAccount() {
        val keyA = switch.key("enabled", "user-a")
        val keyB = switch.key("enabled", "user-b")
        check(keyA != keyB) { "不同账号的 key 必须不同——否则状态会串账号" }
        check(keyA.contains("user-a")) { "key 必须含 userId，实际 $keyA" }
        check(keyB.contains("user-b")) { "key 必须含 userId，实际 $keyB" }
    }

    @Test
    fun missingUserFailsOpenAndWritesNothing() {
        // 沿用源码注释里的原语义：无账号时 isEnabled fail-open 返回 true。
        currentUser = null
        check(switch.isEnabled(ctx)) { "无账号时应 fail-open 返回 true" }
        check(!switch.isUserSet(ctx)) { "无账号时应视为未设置" }

        // setEnabled 在无账号时静默不写——prefs 里不应留下任何键
        switch.setEnabled(ctx, false)
        val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        check(prefs.all.isEmpty()) { "无账号时 setEnabled 不该写入任何键，实际写入了 ${prefs.all.keys}" }

        // applyServerDefault 同理
        switch.applyServerDefault(ctx, false)
        check(prefs.all.isEmpty()) { "无账号时 applyServerDefault 不该写入任何键，实际 ${prefs.all.keys}" }
    }

    /**
     * 只断言「注入的 provider 被真的用上了」。
     *
     * ⚠️ 原来的版本还想顺便断言「空 userId 应视为无账号」，**那条删掉了**：
     * `takeIf { it.isNotBlank() }` 过滤写在**默认** provider（`TokenManager` 那条）里，
     * 而本测试把 provider 换成了 `{ currentUser }`，换掉之后就不再有那层过滤。
     * 换句话说——这条保护在生产代码里有，但**经由注入测不到**；
     * 想测它就得用默认 provider，而默认 provider 依赖 Keystore（Robolectric 下不可用）。
     */
    @Test
    fun injectedUserIdProviderIsActuallyUsed() {
        currentUser = "user-a"
        check(switch.userId(ctx) == "user-a") { "应原样返回注入的 userId" }
        currentUser = "user-z"
        check(switch.userId(ctx) == "user-z") { "换一个账号应立即生效（证明不是缓存）" }
        currentUser = null
        check(switch.userId(ctx) == null) { "provider 返回 null 时应透传" }
    }
}
