package com.maodouchat.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G209b：`AppLocaleManager` 的语言模式归一化。
 *
 * 被 6 个文件引用，此前零测试。这里的性质听起来很小，但它是**唯一**决定
 * 「界面到底用中文还是英文」的那个值，而且它来自 SharedPreferences——
 * 一个能被人手工改坏/被旧版本写脏的持久化字段。
 *
 * 两条双向防线都值得钉：
 * 1. `setMode` **写入前**归一化：垃圾模式 → `MODE_SYSTEM`（绝不把 "fr" 存进去）；
 * 2. `getMode` **读出时**再校验一次（`takeIf { it in supportedModes }`）——
 *    防的是 prefs 被外部写脏的情况（卸载残留 / 手工 adb 注入 / 旧版本写过别的值）。
 *
 * 两道同时存在是纵深防御：任何一道失效，另一道仍会把界面拉回「跟随系统」，
 * 而不会卡在一个不存在的语言上。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLocaleManagerTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs by lazy {
        ctx.getSharedPreferences("general_settings", Context.MODE_PRIVATE)
    }

    @Before
    fun reset() {
        prefs.edit().clear().apply()
    }

    @Test
    fun unsetModeFallsBackToSystem() {
        assertEquals(AppLocaleManager.MODE_SYSTEM, AppLocaleManager.getMode(ctx),
            "从没设置过时应跟随系统")
    }

    @Test
    fun eachSupportedModeRoundTrips() {
        listOf(
            AppLocaleManager.MODE_SYSTEM,
            AppLocaleManager.MODE_CHINESE,
            AppLocaleManager.MODE_ENGLISH,
        ).forEach { mode ->
            AppLocaleManager.setMode(ctx, mode)
            assertEquals(mode, AppLocaleManager.getMode(ctx), "设置 $mode 后应原样读回")
        }
    }

    @Test
    fun setModeNormalizesUnsupportedValuesBeforeWriting() {
        // 绝不把不认识的值存进去（存了会让 getMode 的校验成为唯一防线）
        listOf("fr", "ja", "", "ZH", "En", "system ", null.orEmpty(), "zh-CN").forEach { bad ->
            AppLocaleManager.setMode(ctx, bad)
            val stored = prefs.getString("language_mode", null)
            assertEquals(
                AppLocaleManager.MODE_SYSTEM, stored,
                "模式 '$bad' 写入前必须归一化为 system，实际存了 '$stored'",
            )
        }
    }

    private fun String?.orEmpty(): String = this ?: ""

    @Test
    fun getModeRevalidatesADirtyStoredValue() {
        // 第二道防线：prefs 被外部写脏时，读出来仍必须回到 system。
        // （setMode 归一化拦得住自己人，拦不住手工注入。）
        listOf("fr", "ja", "", "ZH", "zh_CN", "en-US").forEach { dirty ->
            prefs.edit().putString("language_mode", dirty).apply()
            assertEquals(
                AppLocaleManager.MODE_SYSTEM, AppLocaleManager.getMode(ctx),
                "存储值 '$dirty' 不在白名单，读取时必须回落到 system",
            )
        }
    }

    @Test
    fun languageTagsMatchTheDocumentedMapping() {
        // languageTag 是 private，这里经 SDK 33+ 的 LocaleManager.applicationLocales
        // 间接观察（setMode 会把 tag 交给系统）。null/空集 = tag 为空（system）。
        val localeManager = ctx.getSystemService(android.app.LocaleManager::class.java)
        AppLocaleManager.setMode(ctx, AppLocaleManager.MODE_CHINESE)
        assertEquals("zh-CN", localeManager.applicationLocales[0]?.toLanguageTag(),
            "中文模式应落到 zh-CN")

        AppLocaleManager.setMode(ctx, AppLocaleManager.MODE_ENGLISH)
        assertEquals("en", localeManager.applicationLocales[0]?.toLanguageTag(),
            "英文模式应落到 en")

        AppLocaleManager.setMode(ctx, AppLocaleManager.MODE_SYSTEM)
        assertTrue(localeManager.applicationLocales.isEmpty,
            "跟随系统应对应空 LocaleList（让系统自己决定），实际 ${localeManager.applicationLocales}")
    }

    @Test
    fun wrapKeepsContextWhenFollowingSystem() {
        // tag 为空（system）时 wrap 应原样返回，不包一层 Configuration
        AppLocaleManager.setMode(ctx, AppLocaleManager.MODE_SYSTEM)
        val wrapped = AppLocaleManager.wrap(ctx)
        assertEquals(ctx, wrapped, "跟随系统时 wrap 应返回原 context")
    }
}
