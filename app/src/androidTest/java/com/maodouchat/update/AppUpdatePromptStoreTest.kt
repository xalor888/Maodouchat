package com.maodouchat.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G329c：`AppUpdatePromptStore` 的契约测试（Q03 第 3 项「更新器仪器测试」的入口）。
 *
 * 这个 19 行的 `object` 在此前**零测试覆盖**，而它编码的是一条真实的 UX 正确性
 * 属性（见它自己的 KDoc）：
 *
 *     「同一 versionCode 只弹一次；用户点稍后后等下一版再弹。」
 *
 * 这条属性坏了用户不会报错，只会被反复骚扰、或者相反——升了新版却不再被提示。
 * 属于典型的「静默腐烂」，所以值得钉住。
 *
 * 它是无状态单例 + SharedPreferences，仪器测试里真机 context 现成可用，
 * **不需要任何 fake，也不需要改生产签名**（不为了好测而把 object 改成 class）。
 *
 * 纪律同 G321c：**状态必须可清理**。SharedPreferences 在仪器测试里是按包名
 * 持久化的真实文件，所以每个用例前 `clear()`，且用「读回是否为 0」来验证
 * 清理真的生效——不是换个 key 糊过去。
 */
@RunWith(AndroidJUnit4::class)
class AppUpdatePromptStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 与 `AppUpdatePromptStore` 内部同一个 pref 文件名（它是 private，测试侧只能按名清理）。 */
    private val prefs get() = context.getSharedPreferences("app_update_prompt", android.content.Context.MODE_PRIVATE)

    @Before
    fun clearPromptState() {
        prefs.edit().clear().commit()
        // 清理是否真的生效，用被测函数自己验证——而不是假设。
        check(AppUpdatePromptStore.lastOfferedVersionCode(context) == 0) {
            "清理后 lastOfferedVersionCode 应回到 0"
        }
    }

    @After
    fun cleanUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun freshInstallHasNeverBeenOfferedAnUpdate() {
        // 从没提示过 → 默认 0（这是「首启要不要弹」的判断基准）
        assert(AppUpdatePromptStore.lastOfferedVersionCode(context) == 0) {
            "全新安装应返回 0，实际 ${AppUpdatePromptStore.lastOfferedVersionCode(context)}"
        }
    }

    @Test
    fun afterMarkingOfferedTheVersionCodeIsRemembered() {
        AppUpdatePromptStore.markOffered(context, 1085)
        assert(AppUpdatePromptStore.lastOfferedVersionCode(context) == 1085) {
            "markOffered(1085) 后应读回 1085，实际 ${AppUpdatePromptStore.lastOfferedVersionCode(context)}"
        }
    }

    @Test
    fun markingTheSameVersionAgainIsIdempotent() {
        // 「同一 versionCode 只弹一次」的可执行形式：重复标记不得把状态弄丢或改坏。
        AppUpdatePromptStore.markOffered(context, 1085)
        AppUpdatePromptStore.markOffered(context, 1085)
        assert(AppUpdatePromptStore.lastOfferedVersionCode(context) == 1085) {
            "重复 markOffered 同一版本应仍为 1085，实际 ${AppUpdatePromptStore.lastOfferedVersionCode(context)}"
        }
    }

    @Test
    fun markingANewerVersionReplacesTheOldOne() {
        // 「用户点稍后后等下一版再弹」的可执行形式：升到更高 versionCode 后
        // 必须能被记住，否则新版永远不再提示。
        AppUpdatePromptStore.markOffered(context, 1085)
        AppUpdatePromptStore.markOffered(context, 1100)
        assert(AppUpdatePromptStore.lastOfferedVersionCode(context) == 1100) {
            "markOffered(1100) 应覆盖为 1100，实际 ${AppUpdatePromptStore.lastOfferedVersionCode(context)}"
        }
    }
}
