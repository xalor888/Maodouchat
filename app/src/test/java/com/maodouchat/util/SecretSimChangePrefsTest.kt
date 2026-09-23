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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G182c：`SecretSimChangePrefs` —— SIM 变更检测的「时间戳只在真变更时刷新」。
 *
 * 这个类此前的引用数是 0（G182c 扫描实测），而它守的是一条安全语义：
 * `lastChangeAt` 只在 SIM **真的变了**时刷新。写反的后果是
 * 「同一张卡被重复上报」被当成「换了卡」，触发不必要的安全告警/风控。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretSimChangePrefsTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val userId = "user-under-test"

    @Before
    fun reset() {
        ctx.getSharedPreferences("secret_sim_change", Context.MODE_PRIVATE).edit().clear().apply()
        AccountFeatureSwitch.userIdOverrideForTest = { userId }
    }

    @After
    fun tearDown() {
        AccountFeatureSwitch.userIdOverrideForTest = null
    }

    @Test
    fun firstWriteDoesNotStampAChange() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        // 首次只是「记录基线」，不算变更
        assertEquals(0L, SecretSimChangePrefs.lastChangeAt(c), "首次设置不应写 lastChangeAt")
        assertEquals("sim-1", SecretSimChangePrefs.lastSimId(c))
    }

    @Test
    fun sameSimIdRepeatedDoesNotRefreshTheStamp() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        assertEquals(
            0L, SecretSimChangePrefs.lastChangeAt(c),
            "同一张卡重复上报不是换卡——lastChangeAt 必须保持 0，否则会误告警",
        )
    }

    @Test
    fun aRealChangeStampsTheTimestamp() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        SecretSimChangePrefs.setLastSimId(c, "sim-2")
        assertTrue(SecretSimChangePrefs.lastChangeAt(c) > 0L, "真换卡必须刷新 lastChangeAt")
        assertEquals("sim-2", SecretSimChangePrefs.lastSimId(c))
    }

    @Test
    fun blankSimIdIsIgnored() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        SecretSimChangePrefs.setLastSimId(c, "   ")
        assertEquals("sim-1", SecretSimChangePrefs.lastSimId(c), "空白 simId 不该覆盖已有值")
        assertEquals(0L, SecretSimChangePrefs.lastChangeAt(c), "空白 simId 不该算一次换卡")
    }

    @Test
    fun storedSimIdIsTrimmed() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "  sim-1  ")
        assertEquals("sim-1", SecretSimChangePrefs.lastSimId(c), "读出来应该已经 trim 过")
    }
}
