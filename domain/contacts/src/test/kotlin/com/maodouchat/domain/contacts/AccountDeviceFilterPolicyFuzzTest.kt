package com.maodouchat.domain.contacts

import com.maodouchat.core.model.DeviceInfoDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AccountDeviceFilterPolicy] (checklist line 931, 36th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the device-name / device-id / status search from the
 * documented rules. Note that matches() does not trim the query (filter()
 * does), and that isCurrent / identityKey never participate in matching.
 * A Python mini-model differentially checks both oracles on 5000 random
 * inputs each (0 diffs) before this file was written.
 */
class AccountDeviceFilterPolicyFuzzTest {

    private fun oracleMatches(device: DeviceInfoDto, query: String): Boolean =
        device.deviceName.contains(query, ignoreCase = true) ||
            device.deviceId.toString().contains(query, ignoreCase = true) ||
            device.status.contains(query, ignoreCase = true)

    private fun oracleFilter(devices: List<DeviceInfoDto>, query: String): List<DeviceInfoDto> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return devices
        return devices.filter { oracleMatches(it, trimmed) }
    }

    private val deviceNames = listOf("Pixel 8", "我的设备", "iPhone", "Mi 14", "  ", "", "Pixel 8 Pro")
    private val statuses = listOf("CONFIRMED", "PENDING", "confirmed", "", "Revoked")

    private fun randomDevice(rnd: Random): DeviceInfoDto {
        val deviceId = when (rnd.nextInt(8)) {
            0 -> 0
            1 -> -1
            2 -> -12
            3 -> Int.MIN_VALUE
            4 -> Int.MAX_VALUE
            5 -> rnd.nextInt(-100, 100)
            else -> rnd.nextInt(0, 1000)
        }
        return DeviceInfoDto(
            userId = "u1",
            deviceId = deviceId,
            deviceName = deviceNames.random(rnd),
            identityKey = "key${rnd.nextInt(100)}",
            status = statuses.random(rnd),
            isCurrent = rnd.nextBoolean()
        )
    }

    private fun randomSubstring(rnd: Random, s: String): String {
        if (s.isEmpty()) return ""
        val start = rnd.nextInt(s.length)
        return s.substring(start, rnd.nextInt(start, s.length + 1))
    }

    private fun randomQuery(rnd: Random, device: DeviceInfoDto): String {
        val fields = listOf(device.deviceName, device.deviceId.toString(), device.status)
        val query = when (rnd.nextInt(10)) {
            in 0..4 -> randomSubstring(rnd, fields.random(rnd))
            5 -> listOf("pixel", "PIXEL", "我的", "confirmed", "PENDING", "12", "-").random(rnd)
            6 -> ""
            7 -> "   "
            8 -> "zzz"
            else -> listOf("  pixel  ", " CONFIRMED", "12 ", "  ").random(rnd)
        }
        return when (rnd.nextInt(4)) {
            0 -> query.uppercase()
            1 -> query.lowercase()
            else -> query
        }
    }

    @Test
    fun matchesRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val device = randomDevice(rnd)
            val query = randomQuery(rnd, device)
            assertEquals(
                oracleMatches(device, query),
                AccountDeviceFilterPolicy.matches(device, query),
                "device=$device query=<$query>"
            )
        }
    }

    @Test
    fun filterRandomOracle() {
        val rnd = Random(36)
        repeat(200) {
            val devices = List(rnd.nextInt(0, 7)) { randomDevice(rnd) }
            val query = if (devices.isEmpty()) randomQuery(rnd, randomDevice(rnd))
                else randomQuery(rnd, devices.random(rnd))
            assertEquals(
                oracleFilter(devices, query),
                AccountDeviceFilterPolicy.filter(devices, query),
                "query=<$query>"
            )
        }
    }

    @Test
    fun numericQueryOnlyMatchesDeviceId() {
        val rnd = Random(36)
        // Names and statuses carry no digits here, so a numeric query can only
        // hit through deviceId.toString().
        val digitFreeNames = listOf("Pixel", "我的设备", "iPhone")
        val digitFreeStatuses = listOf("CONFIRMED", "PENDING")
        val numericQueries = listOf("1", "12", "-", "0", "9", "2147483648", "42")
        repeat(200) {
            val device = DeviceInfoDto(
                userId = "u1",
                deviceId = rnd.nextInt(-1000, 1000),
                deviceName = digitFreeNames.random(rnd),
                identityKey = "key",
                status = digitFreeStatuses.random(rnd)
            )
            val query = numericQueries.random(rnd)
            assertEquals(
                device.deviceId.toString().contains(query, ignoreCase = true),
                AccountDeviceFilterPolicy.matches(device, query),
                "device=$device query=<$query>"
            )
        }
    }

    @Test
    fun filterSubsequenceInvariant() {
        val rnd = Random(36)
        repeat(200) {
            val devices = List(rnd.nextInt(0, 7)) { randomDevice(rnd) }
            val query = if (devices.isEmpty()) "pixel" else randomQuery(rnd, devices.random(rnd))
            val result = AccountDeviceFilterPolicy.filter(devices, query)
            var cursor = 0
            for (device in result) {
                val found = devices.subList(cursor, devices.size).indexOf(device)
                assertTrue(found >= 0, "result not a subsequence: query=<$query>")
                cursor += found + 1
            }
            val trimmed = query.trim()
            for (device in result) {
                assertTrue(oracleMatches(device, trimmed), "result must match: query=<$query>")
            }
            if (trimmed.isBlank()) assertEquals(devices, result, "blank query returns all")
        }
    }

    @Test
    fun pins() {
        val d1 = DeviceInfoDto(
            userId = "u1", deviceId = -12, deviceName = "Pixel 8",
            identityKey = "k", status = "CONFIRMED"
        )
        assertTrue(AccountDeviceFilterPolicy.matches(d1, "-"), "negative id contains -")
        assertTrue(AccountDeviceFilterPolicy.matches(d1, "12"), "id substring")
        assertTrue(AccountDeviceFilterPolicy.matches(d1, "pixel"), "name ignoreCase")
        assertTrue(AccountDeviceFilterPolicy.matches(d1, "confirmed"), "status ignoreCase")
        assertTrue(AccountDeviceFilterPolicy.matches(d1, "Pixel 8"), "full name")
        assertFalse(AccountDeviceFilterPolicy.matches(d1, "k"), "identityKey never matches")
        val d2 = DeviceInfoDto(
            userId = "u1", deviceId = 0, deviceName = "我的设备",
            identityKey = "k", status = "PENDING"
        )
        assertTrue(AccountDeviceFilterPolicy.matches(d2, "我的"), "default CJK name")
        assertTrue(AccountDeviceFilterPolicy.matches(d2, "0"), "zero id")
        assertTrue(AccountDeviceFilterPolicy.matches(d2, "pending"), "status ignoreCase")
        val devices = listOf(d1, d2)
        assertEquals(devices, AccountDeviceFilterPolicy.filter(devices, "  "), "blank query returns all")
        assertEquals(listOf(d1), AccountDeviceFilterPolicy.filter(devices, "  pixel "), "filter trims query")
        assertEquals(emptyList(), AccountDeviceFilterPolicy.filter(devices, "zzz"), "no match")
    }
}
