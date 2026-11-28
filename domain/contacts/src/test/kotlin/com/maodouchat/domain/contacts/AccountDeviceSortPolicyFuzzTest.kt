package com.maodouchat.domain.contacts

import com.maodouchat.core.model.DeviceInfoDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [AccountDeviceSortPolicy] (checklist
 * line 931, 60th block). Test-only, zero production changes.
 *
 * The oracle re-implements the sort by hand: split into currents /
 * others preserving input order, insertion-sort each run by deviceId
 * (stable: equal ids keep input order), currents first. Random lists
 * use Random(42) with a small device-id pool so ties are dense.
 */
class AccountDeviceSortPolicyFuzzTest {

// ---------- C1 AccountDeviceSortPolicy oracle ----------

// Hand-written stable sort: split into currents / others preserving
// input order, insertion-sort each run by deviceId (only moves on ">",
// so equal ids keep their input order), currents first. Never calls
// sortedWith / compareBy — no shared code path with production.
private fun insertionSortById(list: ArrayList<DeviceInfoDto>): List<DeviceInfoDto> {
    for (i in 1 until list.size) {
        val v = list[i]
        var j = i - 1
        while (j >= 0 && list[j].deviceId > v.deviceId) {
            list[j + 1] = list[j]
            j--
        }
        list[j + 1] = v
    }
    return list
}

private fun sortOracle(devices: List<DeviceInfoDto>): List<DeviceInfoDto> {
    val currents = ArrayList<DeviceInfoDto>()
    val others = ArrayList<DeviceInfoDto>()
    for (d in devices) (if (d.isCurrent) currents else others).add(d)
    return insertionSortById(currents) + insertionSortById(others)
}

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended (with the
// multi-code-unit uppercase expansions), ligatures, fullwidth forms,
// Deseret / Adlam / Medefaidrin / Vithkuqi astral letters, CJK, combining
// marks, digits of several scripts, and general punctuation. Osage small
// letters are excluded: JDK 17 leaves them unchanged on uppercase while
// newer Unicode data maps them (pinned separately).
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0660 to 0x0660, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E60 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F600 to 0x1F600
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> {
                // Lone surrogate (high or low): survives take/uppercase
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 42; 4 -> -42
    5 -> Int.MIN_VALUE; 6 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 42L; 4 -> -42L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

private fun randomNullableLong(rnd: Random): Long? =
    if (rnd.nextInt(4) == 0) null else randomLong(rnd)

private fun randomNullableInt(rnd: Random): Int? =
    if (rnd.nextInt(4) == 0) null else randomInt(rnd)


// Small device-id pool on purpose: ties on (isCurrent, deviceId) are
// what exercises sort stability.
private fun randomDeviceId(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 5; 5 -> 8; 6 -> -1
    7 -> Int.MIN_VALUE; 8 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomDevice(rnd: Random): DeviceInfoDto = DeviceInfoDto(
    userId = randomString(rnd),
    deviceId = randomDeviceId(rnd),
    deviceName = randomString(rnd),
    identityKey = randomString(rnd),
    lastSeenAt = randomNullableLong(rnd),
    isCurrent = rnd.nextBoolean(),
    status = randomString(rnd),
    confirmedAt = randomNullableLong(rnd),
    confirmedByDeviceId = randomNullableInt(rnd)
)

    @Test
    fun sortRandom() {
        val rnd = Random(42)
        repeat(300) {
            val n = when (rnd.nextInt(8)) {
                0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 5; 5 -> 8; 6 -> 13
                else -> rnd.nextInt(0, 13)
            }
            val devices = List(n) { randomDevice(rnd) }
            assertEquals(sortOracle(devices), AccountDeviceSortPolicy.sort(devices))
        }
    }

    @Test
    fun pinnedEdgeCases() {

        // Local device builder: only id / current / name vary here;
        // the other fields ride along to prove the sort keeps them.
        fun dev(id: Int, cur: Boolean = false, name: String = "d"): DeviceInfoDto =
            DeviceInfoDto(userId = "u1", deviceId = id, deviceName = name,
                identityKey = "key", isCurrent = cur)
        // Empty and single-element lists pass through.
        assertEquals(emptyList<DeviceInfoDto>(), AccountDeviceSortPolicy.sort(emptyList()))
        assertEquals(listOf(3), AccountDeviceSortPolicy.sort(listOf(dev(3))).map { it.deviceId })
        assertEquals(listOf(3), AccountDeviceSortPolicy.sort(listOf(dev(3, true))).map { it.deviceId })
        // current device comes first even with a larger id.
        assertEquals(listOf(9, 1, 5),
            AccountDeviceSortPolicy.sort(listOf(dev(1, false, "d"), dev(9, true, "d"), dev(5, false, "d"))).map { it.deviceId })
        // non-current devices sort by device id ascending.
        assertEquals(listOf(10, 20, 30),
            AccountDeviceSortPolicy.sort(listOf(dev(30, false, "d"), dev(10, false, "d"), dev(20, false, "d"))).map { it.deviceId })
        // currents sort by device id ascending among themselves.
        assertEquals(listOf(3, 7),
            AccountDeviceSortPolicy.sort(listOf(dev(7, true, "d"), dev(3, true, "d"))).map { it.deviceId })
        // ties keep input order (stability, non-current).
        assertEquals(listOf(5, 5, 5),
            AccountDeviceSortPolicy.sort(listOf(dev(5, false, "first"), dev(5, false, "second"), dev(5, false, "third"))).map { it.deviceId })
        // ties keep input order (stability, mixed current).
        assertEquals(listOf(3, 5, 5, 5),
            AccountDeviceSortPolicy.sort(listOf(dev(5, true, "first"), dev(5, true, "second"), dev(3, true, "third"), dev(5, false, "fourth"))).map { it.deviceId })
        // Int.MIN_VALUE / Int.MAX_VALUE ids order correctly.
        assertEquals(listOf(-2147483648, 2147483647, -2147483648, 0, 2147483647),
            AccountDeviceSortPolicy.sort(listOf(dev(-2147483648, false, "d"), dev(2147483647, false, "d"), dev(0, false, "d"), dev(-2147483648, true, "d"), dev(2147483647, true, "d"))).map { it.deviceId })
        // same id across current / non-current groups.
        assertEquals(listOf(1, 1, 2, 0, 1, 1),
            AccountDeviceSortPolicy.sort(listOf(dev(1, true, "d"), dev(1, false, "d"), dev(1, true, "d"), dev(1, false, "d"), dev(0, false, "d"), dev(2, true, "d"))).map { it.deviceId })
        // Full-list equality on the tie case: names prove the
        // input order survived, not just the ids.
        assertEquals(
            listOf(DeviceInfoDto(userId = "a", deviceId = 5, deviceName = "first", identityKey = "key", lastSeenAt = null, isCurrent = false, status = "CONFIRMED", confirmedAt = null, confirmedByDeviceId = null), DeviceInfoDto(userId = "b", deviceId = 5, deviceName = "second", identityKey = "key", lastSeenAt = null, isCurrent = false, status = "CONFIRMED", confirmedAt = null, confirmedByDeviceId = null), DeviceInfoDto(userId = "c", deviceId = 5, deviceName = "third", identityKey = "key", lastSeenAt = null, isCurrent = false, status = "CONFIRMED", confirmedAt = null, confirmedByDeviceId = null)),
            AccountDeviceSortPolicy.sort(listOf(DeviceInfoDto(userId = "a", deviceId = 5, deviceName = "first", identityKey = "key", lastSeenAt = null, isCurrent = false, status = "CONFIRMED", confirmedAt = null, confirmedByDeviceId = null), DeviceInfoDto(userId = "b", deviceId = 5, deviceName = "second", identityKey = "key", lastSeenAt = null, isCurrent = false, status = "CONFIRMED", confirmedAt = null, confirmedByDeviceId = null), DeviceInfoDto(userId = "c", deviceId = 5, deviceName = "third", identityKey = "key", lastSeenAt = null, isCurrent = false, status = "CONFIRMED", confirmedAt = null, confirmedByDeviceId = null))))
        // Nullable Long/Int fields ride along untouched.
        assertEquals(
            listOf(DeviceInfoDto(userId = "u", deviceId = 3, deviceName = "n", identityKey = "k", lastSeenAt = Long.MAX_VALUE, isCurrent = true, status = "s", confirmedAt = Long.MIN_VALUE, confirmedByDeviceId = 2147483647), DeviceInfoDto(userId = "u", deviceId = 1, deviceName = "n2", identityKey = "k2", lastSeenAt = null, isCurrent = false, status = "s2", confirmedAt = null, confirmedByDeviceId = null)),
            AccountDeviceSortPolicy.sort(listOf(DeviceInfoDto(userId = "u", deviceId = 3, deviceName = "n", identityKey = "k", lastSeenAt = Long.MAX_VALUE, isCurrent = true, status = "s", confirmedAt = Long.MIN_VALUE, confirmedByDeviceId = 2147483647), DeviceInfoDto(userId = "u", deviceId = 1, deviceName = "n2", identityKey = "k2", lastSeenAt = null, isCurrent = false, status = "s2", confirmedAt = null, confirmedByDeviceId = null))))
    }
}
