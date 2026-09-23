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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G182c：`UnreadPriorityPreferences` 是 14 行薄封装（账号隔离 + 默认开）。
 * 这里只验委托语义：开关往返 + 无账号时 fail-open。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnreadPriorityPreferencesTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() {
        ctx.getSharedPreferences("unread_priority", Context.MODE_PRIVATE).edit().clear().apply()
        AccountFeatureSwitch.userIdOverrideForTest = { "user-under-test" }
    }

    @After
    fun tearDown() {
        AccountFeatureSwitch.userIdOverrideForTest = null
    }

    @Test
    fun unreadPriorityRoundTripsThroughPrefs() {
        val c = ctx
        assertTrue(UnreadPriorityPreferences.isEnabled(c), "默认应为开")
        UnreadPriorityPreferences.setEnabled(c, false)
        assertFalse(UnreadPriorityPreferences.isEnabled(c), "关掉后应读回 false")
        UnreadPriorityPreferences.setEnabled(c, true)
        assertTrue(UnreadPriorityPreferences.isEnabled(c))
    }

    @Test
    fun unreadPriorityFailsOpenWithoutAUser() {
        AccountFeatureSwitch.userIdOverrideForTest = { null }
        assertTrue(
            UnreadPriorityPreferences.isEnabled(ctx),
            "无账号时应走 AccountFeatureSwitch 的 fail-open（true）",
        )
    }
}
