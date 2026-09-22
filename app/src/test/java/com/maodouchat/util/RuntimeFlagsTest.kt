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
 * G192b：`RuntimeFlags` 的覆盖。
 *
 * 为什么值得单独立项：它被 **83 个文件**引用，却一条测试都没有。
 * 而它身上有两个不经测试就会悄悄漂掉的性质：
 *
 * 1. **`NEARBY` 与 `CHAT_EXPORT` 是硬编码强制关闭的**
 *    （`isEnabled` 开头 `if (flag == NEARBY || flag == CHAT_EXPORT) return false`，
 *    `setEnabled` 里也被压成 `false`）。这是产品的 kill switch；
 *    没有测试的话，重构时那两行很容易被当成冗余删掉，
 *    于是一个已下线的功能悄无声息地复活。
 * 2. **开关要能真的存进去、读出来**。`setEnabled` 后 `isEnabled` 必须回读一致，
 *    否则「用户在设置里关了某功能却没生效」这类问题无从定位。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RuntimeFlagsTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() {
        ctx.getSharedPreferences("maodou_runtime_flags", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @After
    fun tearDown() {
        ctx.getSharedPreferences("maodou_runtime_flags", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun retiredFlagsStayDisabledNoMatterWhat() {
        // 关停中的两个开关：即使显式 setEnabled(true)，也必须读回 false。
        listOf(RuntimeFlags.NEARBY, RuntimeFlags.CHAT_EXPORT).forEach { flag ->
            RuntimeFlags.setEnabled(ctx, flag, true)
            check(!RuntimeFlags.isEnabled(ctx, flag)) {
                "${flag.key} 是已下线的功能，必须永远读回 false——" +
                    "isEnabled 开头的强制关闭判断被删掉了吗？"
            }
        }
    }

    @Test
    fun retiredFlagsCannotBeReEnabledByTheirStoredValue() {
        // 就算底层 prefs 里被直接写成 true（例如旧版本残留、手工注入），
        // isEnabled 也要压回 false。这才是 kill switch 的意义。
        val prefs = ctx.getSharedPreferences("maodou_runtime_flags", Context.MODE_PRIVATE)
        listOf(RuntimeFlags.NEARBY, RuntimeFlags.CHAT_EXPORT).forEach { flag ->
            prefs.edit().putBoolean(flag.key, true).apply()
            check(!RuntimeFlags.isEnabled(ctx, flag)) {
                "${flag.key} 的存储值被写成 true，isEnabled 仍必须返回 false"
            }
        }
    }

    @Test
    fun enabledFlagRoundTripsThroughPrefs() {
        // 普通开关：关掉再打开，必须原样读回。
        RuntimeFlags.setEnabled(ctx, RuntimeFlags.CHAT_MUTE, false)
        check(!RuntimeFlags.isEnabled(ctx, RuntimeFlags.CHAT_MUTE)) { "关闭后应读回 false" }
        RuntimeFlags.setEnabled(ctx, RuntimeFlags.CHAT_MUTE, true)
        check(RuntimeFlags.isEnabled(ctx, RuntimeFlags.CHAT_MUTE)) { "开启后应读回 true" }
    }

    @Test
    fun defaultIsUsedWhenNothingStored() {
        // 没写过的开关应回落到 Flag 声明里的 default。
        check(RuntimeFlags.isEnabled(ctx, RuntimeFlags.MARKDOWN)) {
            "MARKDOWN 默认 true，未设置时应为 true"
        }
        check(!RuntimeFlags.isEnabled(ctx, RuntimeFlags.FAKE_CHAT)) {
            "FAKE_CHAT 默认 false，未设置时应为 false"
        }
    }

    @Test
    fun everyFlagKeyIsUnique() {
        // 键重复会让两个开关互相顶掉——合并自 98 个 *Prefs 文件最容易出这种错。
        val keys = RuntimeFlags::class.java.declaredFields
            .filter { it.type == RuntimeFlags.Flag::class.java }
            .map { it.isAccessible = true; it.get(null) as RuntimeFlags.Flag }
            .map { it.key }
        check(keys.isNotEmpty()) { "没枚举到任何 Flag" }
        val dup = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        check(dup.isEmpty()) { "Flag key 重复：$dup" }
    }
}
