package com.maodouchat.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G182c：`SecretScreenshotBurnPrefs.shouldPurgeMedia`。
 *
 * 默认 true（截屏即焚连本地解密缓存一并清），用户可关。
 * 无 userId 时 fail-open 返回 true——宁可多清也不少清。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretScreenshotBurnPrefsTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() {
        ctx.getSharedPreferences("secret_screenshot_burn", Context.MODE_PRIVATE).edit().clear().apply()
        AccountFeatureSwitch.userIdOverrideForTest = { "user-under-test" }
    }

    @After
    fun tearDown() {
        AccountFeatureSwitch.userIdOverrideForTest = null
        ctx.getSharedPreferences("secret_screenshot_burn", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun purgeMediaDefaultsToTrueAndCanBeTurnedOff() {
        val c = ctx
        assertTrue(SecretScreenshotBurnPrefs.shouldPurgeMedia(c), "默认应连缓存媒体一起清")
        SecretScreenshotBurnPrefs.setPurgeMedia(c, false)
        assertEquals(false, SecretScreenshotBurnPrefs.shouldPurgeMedia(c), "用户关掉后应不再清缓存")
        SecretScreenshotBurnPrefs.setPurgeMedia(c, true)
        assertTrue(SecretScreenshotBurnPrefs.shouldPurgeMedia(c), "应能再打开")
    }

    @Test
    fun purgeMediaFailsOpenWithoutAUser() {
        AccountFeatureSwitch.userIdOverrideForTest = { null }
        assertTrue(
            SecretScreenshotBurnPrefs.shouldPurgeMedia(ctx),
            "取不到账号时应 fail-open 返回 true——宁可多清，不可漏清",
        )
    }

    @Test
    fun switchDefaultsOnAndRoundTrips() {
        val c = ctx
        assertTrue(SecretScreenshotBurnPrefs.isEnabled(c), "开关默认应开")
        SecretScreenshotBurnPrefs.setEnabled(c, false)
        assertEquals(false, SecretScreenshotBurnPrefs.isEnabled(c), "关掉后应读到关")
        SecretScreenshotBurnPrefs.setEnabled(c, true)
        assertTrue(SecretScreenshotBurnPrefs.isEnabled(c), "应能再打开")
    }

    @Test
    fun serverDefaultAppliesOnlyBeforeUserSets() {
        val c = ctx
        SecretScreenshotBurnPrefs.applyServerDefault(c, false)
        assertEquals(false, SecretScreenshotBurnPrefs.isEnabled(c), "用户没碰过时服务端默认值应生效")
        SecretScreenshotBurnPrefs.setEnabled(c, true)
        SecretScreenshotBurnPrefs.applyServerDefault(c, false)
        assertTrue(SecretScreenshotBurnPrefs.isEnabled(c), "用户显式设置后服务端默认值不应覆盖")
    }

    @Test
    fun isUserSetReflectsExplicitChoice() {
        val c = ctx
        assertEquals(false, SecretScreenshotBurnPrefs.isUserSet(c), "没设置过应为 false")
        SecretScreenshotBurnPrefs.applyServerDefault(c, false)
        assertEquals(false, SecretScreenshotBurnPrefs.isUserSet(c), "服务端默认值不算用户设置")
        SecretScreenshotBurnPrefs.setEnabled(c, true)
        assertTrue(SecretScreenshotBurnPrefs.isUserSet(c), "setEnabled 后应为 true")
    }

    @Test
    fun purgeMediaIsIsolatedPerAccount() {
        val c = ctx
        SecretScreenshotBurnPrefs.setPurgeMedia(c, false)
        AccountFeatureSwitch.userIdOverrideForTest = { "other-user" }
        assertTrue(SecretScreenshotBurnPrefs.shouldPurgeMedia(c), "换账号后应读到另一个账号的默认值 true")
        SecretScreenshotBurnPrefs.setPurgeMedia(c, true)
        AccountFeatureSwitch.userIdOverrideForTest = { "user-under-test" }
        assertEquals(false, SecretScreenshotBurnPrefs.shouldPurgeMedia(c), "切回原账号应仍是它自己的 false")
    }

    @Test
    fun switchFailsOpenWithoutAUser() {
        AccountFeatureSwitch.userIdOverrideForTest = { null }
        assertTrue(SecretScreenshotBurnPrefs.isEnabled(ctx), "取不到账号时开关应 fail-open 返回 true")
        assertEquals(false, SecretScreenshotBurnPrefs.isUserSet(ctx), "取不到账号时 isUserSet 应为 false")
        SecretScreenshotBurnPrefs.setEnabled(ctx, false)
        assertTrue(SecretScreenshotBurnPrefs.isEnabled(ctx), "无账号时 setEnabled 不应留下任何状态")
    }
}
