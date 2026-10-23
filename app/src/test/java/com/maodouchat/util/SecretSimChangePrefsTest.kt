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
 * 写反的后果是「同一张卡被重复上报」被当成「换了卡」，触发不必要的安全告警。
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

    @Test
    fun switchDefaultsOnAndRoundTrips() {
        val c = ctx
        assertTrue(SecretSimChangePrefs.isEnabled(c), "开关默认应开")
        SecretSimChangePrefs.setEnabled(c, false)
        assertEquals(false, SecretSimChangePrefs.isEnabled(c), "关掉后应读到关")
        SecretSimChangePrefs.setEnabled(c, true)
        assertTrue(SecretSimChangePrefs.isEnabled(c), "应能再打开")
    }

    @Test
    fun serverDefaultAppliesOnlyBeforeUserSets() {
        val c = ctx
        SecretSimChangePrefs.applyServerDefault(c, false)
        assertEquals(false, SecretSimChangePrefs.isEnabled(c), "用户没碰过时服务端默认值应生效")
        assertEquals(false, SecretSimChangePrefs.isUserSet(c), "服务端默认值不算用户设置")
        SecretSimChangePrefs.setEnabled(c, true)
        SecretSimChangePrefs.applyServerDefault(c, false)
        assertTrue(SecretSimChangePrefs.isEnabled(c), "用户显式设置后服务端默认值不应覆盖")
        assertTrue(SecretSimChangePrefs.isUserSet(c), "setEnabled 后应标记为用户已设置")
    }

    @Test
    fun whitespaceVariantOfSameIdDoesNotStamp() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        SecretSimChangePrefs.setLastSimId(c, "  sim-1  ")
        assertEquals(0L, SecretSimChangePrefs.lastChangeAt(c), "只是多了空白的同一张卡不是换卡")
        assertEquals("sim-1", SecretSimChangePrefs.lastSimId(c))
    }

    @Test
    fun simStateIsIsolatedPerAccount() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-a")
        SecretSimChangePrefs.setLastSimId(c, "sim-b")
        val stamp = SecretSimChangePrefs.lastChangeAt(c)
        assertTrue(stamp > 0L, "真换卡应有时间戳")
        AccountFeatureSwitch.userIdOverrideForTest = { "other-user" }
        assertNull(SecretSimChangePrefs.lastSimId(c), "换账号后应读不到原账号的 sim")
        assertEquals(0L, SecretSimChangePrefs.lastChangeAt(c), "换账号后时间戳也应是 0")
        AccountFeatureSwitch.userIdOverrideForTest = { userId }
        assertEquals("sim-b", SecretSimChangePrefs.lastSimId(c), "切回原账号状态应完整")
        assertEquals(stamp, SecretSimChangePrefs.lastChangeAt(c), "切回原账号时间戳应不变")
    }

    @Test
    fun allApisNoOpWithoutAUser() {
        AccountFeatureSwitch.userIdOverrideForTest = { null }
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        assertNull(SecretSimChangePrefs.lastSimId(c), "无账号时写入应静默丢弃")
        assertEquals(0L, SecretSimChangePrefs.lastChangeAt(c), "无账号时时间戳恒为 0")
        assertTrue(SecretSimChangePrefs.isEnabled(c), "无账号时开关 fail-open 返回 true")
    }

    @Test
    fun changeBackAndForthStampsEachRealChange() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        SecretSimChangePrefs.setLastSimId(c, "sim-2")
        val stamp1 = SecretSimChangePrefs.lastChangeAt(c)
        assertTrue(stamp1 > 0L, "真换卡应有时间戳")
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        assertEquals("sim-1", SecretSimChangePrefs.lastSimId(c), "回切后应读到旧卡")
        assertTrue(
            SecretSimChangePrefs.lastChangeAt(c) >= stamp1,
            "回切也是真变更，时间戳不应退回",
        )
    }

    @Test
    fun repeatedReadsHaveNoSideEffects() {
        val c = ctx
        SecretSimChangePrefs.setLastSimId(c, "sim-1")
        repeat(3) { assertEquals("sim-1", SecretSimChangePrefs.lastSimId(c)) }
        repeat(3) { assertEquals(0L, SecretSimChangePrefs.lastChangeAt(c), "读操作不应凭空产生时间戳") }
    }

    @Test
    fun isEnabledIsolatedPerAccount() {
        val c = ctx
        SecretSimChangePrefs.setEnabled(c, false)
        AccountFeatureSwitch.userIdOverrideForTest = { "other-user" }
        assertTrue(SecretSimChangePrefs.isEnabled(c), "换账号后应读到另一个账号的默认开")
        AccountFeatureSwitch.userIdOverrideForTest = { userId }
        assertEquals(false, SecretSimChangePrefs.isEnabled(c), "切回原账号应仍是它自己的关")
    }

    @Test
    fun setEnabledNoOpWithoutAUser() {
        AccountFeatureSwitch.userIdOverrideForTest = { null }
        val c = ctx
        SecretSimChangePrefs.setEnabled(c, false)
        AccountFeatureSwitch.userIdOverrideForTest = { userId }
        assertTrue(SecretSimChangePrefs.isEnabled(c), "无账号时的 setEnabled 不应留下任何状态")
    }

    @Test
    fun applyServerDefaultRepeatedIsStable() {
        val c = ctx
        SecretSimChangePrefs.applyServerDefault(c, false)
        SecretSimChangePrefs.applyServerDefault(c, false)
        assertEquals(false, SecretSimChangePrefs.isEnabled(c), "重复下发服务端默认值应稳定")
        assertEquals(false, SecretSimChangePrefs.isUserSet(c), "重复下发仍不算用户设置")
        SecretSimChangePrefs.setEnabled(c, true)
        SecretSimChangePrefs.applyServerDefault(c, false)
        assertTrue(SecretSimChangePrefs.isEnabled(c), "用户设置后重复下发仍不应覆盖")
    }
}
