package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 自 app 侧 NearbyDistanceLabelTest 随迁并适配 sealed API；半径格式化新增。
class NearbyDistanceTest {

    @Test
    fun `below one kilometre uses the metre branch`() {
        listOf(100, 500, 999).forEach { d ->
            val label = nearbyDistanceLabel(d)
            assertTrue("$d 应走米", label is NearbyDistanceLabel.Meters)
            assertEquals(d, (label as NearbyDistanceLabel.Meters).meters)
        }
    }

    @Test
    fun `tiny and negative distances are coerced up to one hundred metres`() {
        listOf(0, 1, 50, 99, -1, -999).forEach { d ->
            val label = nearbyDistanceLabel(d)
            assertTrue(label is NearbyDistanceLabel.Meters)
            assertEquals("$d 应被 coerce 到 100", 100, (label as NearbyDistanceLabel.Meters).meters)
        }
    }

    @Test
    fun `one kilometre and above uses the kilometre branch`() {
        listOf(1_000, 1_500, 9_999, 12_345).forEach { d ->
            val label = nearbyDistanceLabel(d)
            assertTrue("$d 应走公里", label is NearbyDistanceLabel.Kilometers)
        }
    }

    @Test
    fun `the boundary sits exactly at one thousand`() {
        assertTrue(nearbyDistanceLabel(999) is NearbyDistanceLabel.Meters)
        assertTrue(nearbyDistanceLabel(1_000) is NearbyDistanceLabel.Kilometers)
        assertEquals("1.0", (nearbyDistanceLabel(1_000) as NearbyDistanceLabel.Kilometers).formattedKm)
    }

    @Test
    fun `kilometre strings always carry one decimal place`() {
        listOf(1_000, 2_000, 1_500, 2_500, 12_340, 100_000).forEach { d ->
            val text = (nearbyDistanceLabel(d) as NearbyDistanceLabel.Kilometers).formattedKm
            assertTrue("$d 的公里文案应含小数点：$text", text.contains('.'))
            val decimals = text.substringAfter('.', "")
            assertEquals("$d 的小数位数不对：$text", 1, decimals.length)
        }
    }

    @Test
    fun `the two branches use different kinds`() {
        assertNotEquals(
            nearbyDistanceLabel(500)::class,
            nearbyDistanceLabel(5_000)::class,
        )
    }

    @Test
    fun `the metre argument is never negative`() {
        listOf(-5, -100, -100_000).forEach { d ->
            val meters = (nearbyDistanceLabel(d) as NearbyDistanceLabel.Meters).meters
            assertTrue("$d 的米实参不应为负", meters > 0)
        }
    }

    @Test
    fun `kilometre branch pins exact one-decimal values`() {
        mapOf(
            1_100 to "1.1",
            1_500 to "1.5",
            1_900 to "1.9",
            2_500 to "2.5",
            12_345 to "12.3",
            9_999 to "10.0",
        ).forEach { (d, expected) ->
            val text = (nearbyDistanceLabel(d) as NearbyDistanceLabel.Kilometers).formattedKm
            assertEquals("$d 应格式化为 $expected", expected, text)
        }
    }

    @Test
    fun `whole kilometres keep the trailing zero`() {
        mapOf(2_000 to "2.0", 30_000 to "30.0", 100_000 to "100.0").forEach { (d, expected) ->
            val text = (nearbyDistanceLabel(d) as NearbyDistanceLabel.Kilometers).formattedKm
            assertEquals(expected, text)
        }
    }

    @Test
    fun `extreme values stay in branch without overflow`() {
        val maxLabel = nearbyDistanceLabel(Int.MAX_VALUE)
        assertTrue(maxLabel is NearbyDistanceLabel.Kilometers)
        val minLabel = nearbyDistanceLabel(Int.MIN_VALUE)
        assertTrue(minLabel is NearbyDistanceLabel.Meters)
        assertEquals(100, (minLabel as NearbyDistanceLabel.Meters).meters)
    }

    @Test
    fun `argument types are stable across the branch boundary`() {
        assertTrue(nearbyDistanceLabel(999) is NearbyDistanceLabel.Meters)
        assertTrue(nearbyDistanceLabel(1_000) is NearbyDistanceLabel.Kilometers)
    }

    @Test
    fun `repeated calls return equal labels`() {
        listOf(0, 999, 1_000, 12_345, Int.MAX_VALUE).forEach { d ->
            assertEquals(nearbyDistanceLabel(d), nearbyDistanceLabel(d))
        }
    }

    @Test
    fun `radius options use zero decimals when whole, one otherwise`() {
        mapOf(
            1.0 to "1",
            10.0 to "10",
            0.5 to "0.5",
            1.5 to "1.5",
            2.5 to "2.5",
        ).forEach { (option, expected) ->
            assertEquals("$option 应格式化为 $expected", expected, formatNearbyRadiusOption(option))
        }
    }
}
