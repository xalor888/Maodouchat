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
}
