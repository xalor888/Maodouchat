// Fuzz / random-oracle tests for DeviceSafetyFilterPolicy
// (checklist line 931, 158th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a device
// matches when the decimal device id, the trust-state name, the safety
// code (null counts as empty), or the identity key contains the query
// ignoring case; filter trims the query and returns everything when
// the trimmed query is empty. Case-insensitive containment is the JDK
// regionMatches rule; the oracle calls the same stdlib contains the
// production does, so the two cannot drift on case mapping.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DeviceSafetyState
import com.maodouchat.core.model.IdentityTrustState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun deviceMatchesOracle158(device: DeviceSafetyState, query: String): Boolean =
    device.deviceId.toString().contains(query, ignoreCase = true) ||
        device.trustState.name.contains(query, ignoreCase = true) ||
        device.safetyCode.orEmpty().contains(query, ignoreCase = true) ||
        device.identityKey.orEmpty().contains(query, ignoreCase = true)

internal fun deviceFilterOracle158(devices: List<DeviceSafetyState>, query: String): List<DeviceSafetyState> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return devices
    return devices.filter { deviceMatchesOracle158(it, trimmed) }
}

// ---------- generators ----------

internal val DEVICE_WORD_POOL158 = listOf(
    "hello", "HELLO", "HeLLo", "world", "a", "A", "", " ", "q1", "q2",
    "Σ", "σ", "ς", "ΟΣ", "ος", "οσ", "İ", "ı", "I", "i", "ß", "ẞ",
    "K", "K", "k", "ſ", "s", "S", "µ", "μ", "Μ", "Ǆ", "ǅ", "ǆ",
    "Ÿ", "ÿ", "é", "É", "ﬀ", "张三", "群名", "\u00a0", "\u0085",
    "Alice", "ALICE", "alice", "Bob", "bob", "Al", "ali",
    "UNKNOWN", "TRUSTED", "VERIFIED", "CHANGED", "trust", "verif", "ed",
    "123", "42", "-", "0", "00", "1",
)

internal val DEVICE_ALPHABET158 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003ΣσςİıIißẞK\u212a kſsSµμΜǄǅǆŸÿéÉ\uFB00"

internal val DEVICE_ID_POOL158 = listOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -100, -42, -10, -1, 0, 1, 2, 10, 42,
    100, 123, 1234, 12345, 1000000, Int.MAX_VALUE - 1, Int.MAX_VALUE,
)

internal val DEVICE_TRUST_POOL158 = IdentityTrustState.entries

internal fun randomDeviceChar158(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> DEVICE_ALPHABET158[rnd.nextInt(DEVICE_ALPHABET158.length)].toString()
}

internal fun randomDeviceString158(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomDeviceChar158(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal fun randomDeviceId158(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> DEVICE_ID_POOL158[rnd.nextInt(DEVICE_ID_POOL158.size)]
    1 -> rnd.nextInt(-100, 101)
    else -> rnd.nextInt()
}

internal fun randomDeviceSafetyState158(rnd: Random, id: Int): DeviceSafetyState =
    DeviceSafetyState(
        deviceId = id,
        identityKey = if (rnd.nextInt(10) < 6) DEVICE_WORD_POOL158[rnd.nextInt(DEVICE_WORD_POOL158.size)] else randomDeviceString158(rnd),
        identityFingerprint = "fp",
        trustState = DEVICE_TRUST_POOL158[rnd.nextInt(DEVICE_TRUST_POOL158.size)],
        safetyCode = if (rnd.nextInt(10) < 3) null
            else if (rnd.nextInt(10) < 6) DEVICE_WORD_POOL158[rnd.nextInt(DEVICE_WORD_POOL158.size)]
            else randomDeviceString158(rnd),
    )

internal data class DeviceCase158(
    val devices: List<DeviceSafetyState>,
    val query: String,
)

internal fun randomDeviceCase158(rnd: Random): DeviceCase158 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    val devices = List(count) { i -> randomDeviceSafetyState158(rnd, randomDeviceId158(rnd)) }
    val query = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> if (devices.isNotEmpty()) {
            val base = devices[rnd.nextInt(devices.size)]
            val field = when (rnd.nextInt(4)) {
                0 -> base.deviceId.toString()
                1 -> base.trustState.name
                2 -> base.safetyCode.orEmpty()
                else -> base.identityKey
            }
            when (rnd.nextInt(3)) {
                0 -> field
                1 -> field.uppercase()
                else -> field.lowercase()
            }
        } else {
            randomDeviceString158(rnd)
        }
        else -> randomDeviceString158(rnd)
    }
    return DeviceCase158(devices, query)
}

internal fun deviceJsonEscape158(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun deviceJson158(device: DeviceSafetyState): String {
    val sb = StringBuilder()
    sb.append("{\"id\":").append(device.deviceId)
    sb.append(",\"key\":\"").append(deviceJsonEscape158(device.identityKey)).append("\"")
    sb.append(",\"trust\":\"").append(device.trustState.name).append("\"")
    val code = device.safetyCode
    if (code != null) {
        sb.append(",\"code\":\"").append(deviceJsonEscape158(code)).append("\"")
    }
    sb.append("}")
    return sb.toString()
}

internal fun deviceMatchesWireJson158(device: DeviceSafetyState, query: String): String =
    deviceJson158(device).dropLast(1) + ",\"q\":\"" + deviceJsonEscape158(query) + "\"}"

internal fun deviceFilterWireJson158(case: DeviceCase158): String {
    val sb = StringBuilder()
    sb.append("{\"devices\":[")
    case.devices.forEachIndexed { i, d ->
        if (i > 0) sb.append(",")
        sb.append(deviceJson158(d))
    }
    sb.append("],\"q\":\"").append(deviceJsonEscape158(case.query)).append("\"}")
    return sb.toString()
}

class DeviceSafetyFilterPolicyFuzzTest {

    private fun check(devices: List<DeviceSafetyState>, query: String) {
        for (device in devices) {
            assertEquals(
                deviceMatchesOracle158(device, query),
                DeviceSafetyFilterPolicy.matches(device, query),
            )
        }
        assertEquals(
            deviceFilterOracle158(devices, query),
            DeviceSafetyFilterPolicy.filter(devices, query),
        )
    }

    @Test
    fun pinnedBoundaries() {
        val verified = DeviceSafetyState(
            deviceId = 42, identityKey = "aBcDeF", identityFingerprint = "fp",
            trustState = IdentityTrustState.VERIFIED, safetyCode = "123 456",
        )
        val unknownNull = DeviceSafetyState(
            deviceId = -123, identityKey = "", identityFingerprint = "fp",
            trustState = IdentityTrustState.UNKNOWN, safetyCode = null,
        )
        val changed = DeviceSafetyState(
            deviceId = Int.MIN_VALUE, identityKey = "ﬀ", identityFingerprint = "fp",
            trustState = IdentityTrustState.CHANGED, safetyCode = "",
        )
        // (device, query, expected matches)
        val matchCases = listOf(
            Triple(verified, "42", true),
            Triple(verified, "4", true),
            Triple(verified, "2", true),
            Triple(verified, "43", false),
            Triple(verified, "verif", true),
            Triple(verified, "VERIFIED", true),
            Triple(verified, "trust", false),
            Triple(verified, "ed", true),
            Triple(verified, "123", true),
            Triple(verified, "123 456", true),
            Triple(verified, "456", true),
            Triple(verified, "abcdef", true),
            Triple(verified, "ABCDEF", true),
            Triple(verified, "zzz", false),
            Triple(verified, "", true),
            Triple(unknownNull, "-123", true),
            Triple(unknownNull, "-12", true),
            Triple(unknownNull, "123", true),
            Triple(unknownNull, "unknown", true),
            Triple(unknownNull, "UNKNOWN", true),
            Triple(unknownNull, "trust", false),
            Triple(unknownNull, "x", false),
            Triple(unknownNull, "", true),
            Triple(changed, "-2147483648", true),
            Triple(changed, "2147483648", true),
            Triple(changed, "changed", true),
            Triple(changed, "ged", true),
            Triple(changed, "FF", false),
            Triple(changed, "ﬀ", true),
        )
        for ((device, query, expected) in matchCases) {
            assertEquals(expected, deviceMatchesOracle158(device, query))
            assertEquals(expected, DeviceSafetyFilterPolicy.matches(device, query))
        }
        // (devices, query, expected device ids in order)
        val filterCases = listOf(
            Triple(listOf(verified, unknownNull), "verif", listOf(42)),
            Triple(listOf(verified, unknownNull), "VERIF", listOf(42)),
            Triple(listOf(verified, unknownNull), "  ", listOf(42, -123)),
            Triple(listOf(verified, unknownNull), "", listOf(42, -123)),
            Triple(listOf(verified, unknownNull), "  42  ", listOf(42)),
            Triple(listOf(verified, unknownNull), "ed", listOf(42)),
            Triple(listOf(unknownNull, verified), "1", listOf(-123, 42)),
            Triple(listOf(verified, unknownNull), "zzz", listOf()),
            Triple(listOf(), "verif", listOf()),
        )
        for ((devices, query, expectedIds) in filterCases) {
            assertEquals(expectedIds, deviceFilterOracle158(devices, query).map { it.deviceId })
            assertEquals(expectedIds, DeviceSafetyFilterPolicy.filter(devices, query).map { it.deviceId })
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(158002)
        repeat(2000) {
            val case = randomDeviceCase158(rnd)
            check(case.devices, case.query)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every word as an identity-key / safety-code field against
        // every word query.
        for (field in DEVICE_WORD_POOL158) {
            val device = DeviceSafetyState(
                deviceId = 1, identityKey = field, identityFingerprint = "fp",
                trustState = IdentityTrustState.UNKNOWN, safetyCode = field,
            )
            for (query in DEVICE_WORD_POOL158) {
                assertEquals(
                    deviceMatchesOracle158(device, query),
                    DeviceSafetyFilterPolicy.matches(device, query),
                )
            }
        }
        // Every trust state against every word query (fixed id/key/code
        // chosen to avoid accidental hits: id 987654321, empty key/code).
        for (state in DEVICE_TRUST_POOL158) {
            val device = DeviceSafetyState(
                deviceId = 987654321, identityKey = "", identityFingerprint = "fp",
                trustState = state, safetyCode = null,
            )
            for (query in DEVICE_WORD_POOL158) {
                assertEquals(
                    deviceMatchesOracle158(device, query),
                    DeviceSafetyFilterPolicy.matches(device, query),
                )
            }
        }
        // Device-id sweep: every id in -100..100 against id-shaped queries.
        for (id in -100..100) {
            val device = DeviceSafetyState(
                deviceId = id, identityKey = "", identityFingerprint = "fp",
                trustState = IdentityTrustState.UNKNOWN, safetyCode = null,
            )
            for (query in listOf("1", "-", "0", "00", "42", "-1", "100", "99")) {
                assertEquals(
                    deviceMatchesOracle158(device, query),
                    DeviceSafetyFilterPolicy.matches(device, query),
                )
            }
        }
        // Fixed list against every word query (filter path).
        val list = listOf(
            DeviceSafetyState(1, "AliceKey", "fp", IdentityTrustState.TRUSTED, "111"),
            DeviceSafetyState(2, "BobKey", "fp", IdentityTrustState.VERIFIED, "222"),
            DeviceSafetyState(3, "ΟΣ", "fp", IdentityTrustState.CHANGED, null),
        )
        for (query in DEVICE_WORD_POOL158) {
            assertEquals(
                deviceFilterOracle158(list, query),
                DeviceSafetyFilterPolicy.filter(list, query),
            )
        }
        // Blank-ish queries return the list untouched (NBSP is trimmed;
        // NEL is not -- it filters to nothing instead).
        for (query in listOf("", " ", "  \t ", "\u00a0")) {
            assertEquals(list, deviceFilterOracle158(list, query))
            assertEquals(list, DeviceSafetyFilterPolicy.filter(list, query))
        }
        assertEquals(emptyList(), deviceFilterOracle158(list, "\u0085"))
        assertEquals(emptyList(), DeviceSafetyFilterPolicy.filter(list, "\u0085"))
    }
}
