package com.maodouchat.ui.screen.explore

import com.maodouchat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G163：`ExploreNearbyScreen.nearbyDistanceLabel` 的测试（G163 刚从 formatNearbyDistance 抽出）。
 *
 * 原函数是 `@Composable`，只能靠仪器测试覆盖；抽出后普通 JVM 单测就能逐分支断言。
 * 返回 `(资源 id, 实参)`——米那支实参是 Int，公里那支是已格式化的字符串。
 */
class NearbyDistanceLabelTest {

    private val meters = R.string.explore_nearby_distance_meters
    private val km = R.string.explore_nearby_distance_km

    @Test
    fun `below one kilometre uses the metre branch`() {
        // 只取 ≥100 的值：0/1/50/99 会被 coerce 到 100，由下面那条用例专门盯
        listOf(100, 500, 999).forEach { d ->
            val (res, arg) = nearbyDistanceLabel(d)
            assertEquals("$d 应走米", meters, res)
            assertTrue("$d 的实参应为 Int", arg is Int)
            assertEquals(d, arg)
        }
    }

    @Test
    fun `tiny and negative distances are coerced up to one hundred metres`() {
        // 定位精度有限：「约 0 米」比「约 100 米」更可疑
        listOf(0, 1, 50, 99, -1, -999).forEach { d ->
            val (res, arg) = nearbyDistanceLabel(d)
            assertEquals(meters, res)
            assertEquals("$d 应被 coerce 到 100", 100, arg)
        }
    }

    @Test
    fun `one kilometre and above uses the kilometre branch`() {
        listOf(1_000, 1_500, 9_999, 12_345).forEach { d ->
            val (res, arg) = nearbyDistanceLabel(d)
            assertEquals("$d 应走公里", km, res)
            assertTrue("$d 的实参应为字符串", arg is String)
        }
    }

    @Test
    fun `the boundary sits exactly at one thousand`() {
        // 999 米还是米，1000 米已经是公里
        assertEquals(meters, nearbyDistanceLabel(999).first)
        assertEquals(km, nearbyDistanceLabel(1_000).first)
        // 1_000 米格式化为 "1.0"
        assertEquals("1.0", nearbyDistanceLabel(1_000).second)
    }

    @Test
    fun `kilometre strings always carry one decimal place`() {
        listOf(1_000, 2_000, 1_500, 2_500, 12_340, 100_000).forEach { d ->
            val text = nearbyDistanceLabel(d).second as String
            assertTrue("$d 的公里文案应含小数点：$text", text.contains('.'))
            // 固定一位小数：小数点后恰一位
            val decimals = text.substringAfter('.', "")
            assertEquals("$d 的小数位数不对：$text", 1, decimals.length)
        }
    }

    @Test
    fun `the two branches use different strings`() {
        assertNotEquals(nearbyDistanceLabel(500).first, nearbyDistanceLabel(5_000).first)
    }

    @Test
    fun `the metre argument is never negative`() {
        listOf(-5, -100, -100_000).forEach { d ->
            assertTrue("$d 的米实参不应为负", (nearbyDistanceLabel(d).second as Int) > 0)
        }
    }
}
