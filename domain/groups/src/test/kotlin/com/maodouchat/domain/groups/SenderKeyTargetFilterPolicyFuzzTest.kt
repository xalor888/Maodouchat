// Fuzz / random-oracle tests for SenderKeyTargetFilterPolicy
// (checklist line 931, 159th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: ALL keeps the
// list untouched, otherwise only targets whose status equals the
// filter name ignoring case survive (JDK regionMatches rule, same as
// the stdlib equals the production uses, so the two cannot drift on
// case mapping). Order is preserved; device ids and errors play no
// part in the decision.
package com.maodouchat.domain.groups

import com.maodouchat.core.model.SenderKeyDistributionTargetDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun senderFilterOracle159(
    targets: List<SenderKeyDistributionTargetDto>,
    filter: SenderKeyTargetFilter,
): List<SenderKeyDistributionTargetDto> =
    when (filter) {
        SenderKeyTargetFilter.ALL -> targets
        SenderKeyTargetFilter.FAILED ->
            targets.filter { it.status.equals("FAILED", ignoreCase = true) }
        SenderKeyTargetFilter.PENDING ->
            targets.filter { it.status.equals("PENDING", ignoreCase = true) }
        SenderKeyTargetFilter.SENT ->
            targets.filter { it.status.equals("SENT", ignoreCase = true) }
    }

// ---------- generators ----------

internal val SENDER_STATUS_POOL159 = listOf(
    "FAILED", "failed", "Failed", "FAILEd", "PENDING", "pending", "SENT",
    "sent", "Sent", "", " ", "FAILED ", " FAILED", "FAILED\n", "FAIL",
    "FAILEDD", "SENTX", "UNKNOWN", "DELIVERED", "FAILED\u00a0", "ß",
    "PENDING\u0085",
)

internal val SENDER_ALPHABET159 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003FAILDENSTfaiednst\u03a3\u03c3\u03c2\u0130\u0131I i\u00df\u1e9eK\u212a k\u017fsS\u00b5"

internal val SENDER_DEVICE_ID_POOL159 = listOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -100, -42, -10, -1, 0, 1, 2, 10, 42,
    100, 123, 1234, 12345, 1000000, Int.MAX_VALUE - 1, Int.MAX_VALUE,
)

internal fun randomSenderChar159(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> SENDER_ALPHABET159[rnd.nextInt(SENDER_ALPHABET159.length)].toString()
}

internal fun randomSenderString159(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomSenderChar159(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal fun randomSenderTarget159(rnd: Random, index: Int): SenderKeyDistributionTargetDto =
    SenderKeyDistributionTargetDto(
        userId = "t$index",
        deviceId = when (rnd.nextInt(10)) {
            0 -> SENDER_DEVICE_ID_POOL159[rnd.nextInt(SENDER_DEVICE_ID_POOL159.size)]
            1 -> rnd.nextInt(-100, 101)
            else -> rnd.nextInt()
        },
        status = if (rnd.nextInt(10) < 6) SENDER_STATUS_POOL159[rnd.nextInt(SENDER_STATUS_POOL159.size)] else randomSenderString159(rnd),
        error = if (rnd.nextInt(10) < 3) null else randomSenderString159(rnd),
        updatedAt = rnd.nextLong(),
    )

internal data class SenderCase159(
    val targets: List<SenderKeyDistributionTargetDto>,
    val filter: SenderKeyTargetFilter,
)

internal fun randomSenderCase159(rnd: Random): SenderCase159 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    val targets = List(count) { i -> randomSenderTarget159(rnd, i) }
    return SenderCase159(targets, SenderKeyTargetFilter.entries[rnd.nextInt(4)])
}

internal fun senderJsonEscape159(s: String): String {
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

internal fun senderTargetJson159(target: SenderKeyDistributionTargetDto): String {
    val sb = StringBuilder()
    val error = target.error
    sb.append("{\"userId\":\"").append(senderJsonEscape159(target.userId)).append("\"")
    sb.append(",\"deviceId\":").append(target.deviceId)
    sb.append(",\"status\":\"").append(senderJsonEscape159(target.status)).append("\"")
    if (error != null) {
        sb.append(",\"error\":\"").append(senderJsonEscape159(error)).append("\"")
    }
    sb.append(",\"updatedAt\":").append(target.updatedAt)
    sb.append("}")
    return sb.toString()
}

internal fun senderFilterWireJson159(case: SenderCase159): String {
    val sb = StringBuilder()
    sb.append("{\"targets\":[")
    case.targets.forEachIndexed { i, t ->
        if (i > 0) sb.append(",")
        sb.append(senderTargetJson159(t))
    }
    sb.append("],\"filter\":\"").append(case.filter.name).append("\"}")
    return sb.toString()
}

class SenderKeyTargetFilterPolicyFuzzTest {

    private fun check(targets: List<SenderKeyDistributionTargetDto>, filter: SenderKeyTargetFilter) {
        assertEquals(
            senderFilterOracle159(targets, filter),
            SenderKeyTargetFilterPolicy.filter(targets, filter),
        )
    }

    private fun target(id: String, status: String, deviceId: Int = 1): SenderKeyDistributionTargetDto =
        SenderKeyDistributionTargetDto(
            userId = id, deviceId = deviceId, status = status, updatedAt = 0L,
        )

    @Test
    fun pinnedBoundaries() {
        val mixed = listOf(
            target("t0", "FAILED"),
            target("t1", "failed"),
            target("t2", "Failed"),
            target("t3", "PENDING"),
            target("t4", "pending"),
            target("t5", "SENT"),
            target("t6", "sent"),
            target("t7", "UNKNOWN"),
            target("t8", ""),
        )
        // ALL keeps everything, order untouched.
        assertEquals(mixed, SenderKeyTargetFilterPolicy.filter(mixed, SenderKeyTargetFilter.ALL))
        assertEquals(mixed, senderFilterOracle159(mixed, SenderKeyTargetFilter.ALL))
        // Each non-ALL filter keeps only its own status, any case.
        assertEquals(
            listOf("t0", "t1", "t2"),
            SenderKeyTargetFilterPolicy.filter(mixed, SenderKeyTargetFilter.FAILED).map { it.userId },
        )
        assertEquals(
            listOf("t3", "t4"),
            SenderKeyTargetFilterPolicy.filter(mixed, SenderKeyTargetFilter.PENDING).map { it.userId },
        )
        assertEquals(
            listOf("t5", "t6"),
            SenderKeyTargetFilterPolicy.filter(mixed, SenderKeyTargetFilter.SENT).map { it.userId },
        )
        // Equality is whole-string: trailing space or an extra letter misses.
        val near = listOf(
            target("n0", "FAILED "),
            target("n1", " FAILED"),
            target("n2", "FAILEDD"),
            target("n3", "FAIL"),
            target("n4", "FAILED"),
        )
        assertEquals(
            listOf("n4"),
            SenderKeyTargetFilterPolicy.filter(near, SenderKeyTargetFilter.FAILED).map { it.userId },
        )
        assertEquals(
            emptyList(),
            SenderKeyTargetFilterPolicy.filter(near, SenderKeyTargetFilter.SENT).map { it.userId },
        )
        // Empty list stays empty under every filter.
        for (filter in SenderKeyTargetFilter.entries) {
            assertEquals(emptyList(), SenderKeyTargetFilterPolicy.filter(emptyList(), filter))
        }
        // Case-fold pairs (regionMatches): sigma, İ/ı, ß/ẞ, K/Kelvin.
        assertEquals(
            listOf("s0"),
            SenderKeyTargetFilterPolicy.filter(
                listOf(target("s0", "faıled")),
                SenderKeyTargetFilter.FAILED,
            ).map { it.userId },
        )
        assertEquals(
            listOf("s1"),
            SenderKeyTargetFilterPolicy.filter(
                listOf(target("s1", "ſent")),
                SenderKeyTargetFilter.SENT,
            ).map { it.userId },
        )
        // Device id extremes and errors do not affect the decision.
        val extremes = listOf(
            target("e0", "FAILED", Int.MIN_VALUE),
            target("e1", "FAILED", Int.MAX_VALUE),
            SenderKeyDistributionTargetDto("e2", 42, "FAILED", "boom", 0L),
        )
        assertEquals(
            listOf("e0", "e1", "e2"),
            SenderKeyTargetFilterPolicy.filter(extremes, SenderKeyTargetFilter.FAILED).map { it.userId },
        )
        for (status in SENDER_STATUS_POOL159) {
            for (filter in SenderKeyTargetFilter.entries) {
                check(listOf(target("w", status)), filter)
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(159002)
        repeat(2000) {
            val case = randomSenderCase159(rnd)
            check(case.targets, case.filter)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool status as a single target against every filter.
        for (status in SENDER_STATUS_POOL159) {
            for (filter in SenderKeyTargetFilter.entries) {
                check(listOf(target("w", status)), filter)
            }
        }
        // Fixed mixed list against every filter.
        val mixed = listOf(
            target("t0", "FAILED"), target("t1", "failed"),
            target("t2", "PENDING"), target("t3", "SENT"),
            target("t4", "sent"), target("t5", "UNKNOWN"),
            target("t6", ""), target("t7", "FAILED "),
        )
        for (filter in SenderKeyTargetFilter.entries) {
            check(mixed, filter)
        }
        // Every length 0..20 of all-FAILED lists, every filter.
        for (n in 0..20) {
            val allFailed = List(n) { i -> target("t$i", "FAILED") }
            for (filter in SenderKeyTargetFilter.entries) {
                check(allFailed, filter)
            }
        }
        // Every length 0..20 of all-unknown lists, every filter.
        for (n in 0..20) {
            val allUnknown = List(n) { i -> target("t$i", "UNKNOWN") }
            for (filter in SenderKeyTargetFilter.entries) {
                check(allUnknown, filter)
            }
        }
        // Every device id in the pool as a FAILED target, every filter.
        for (deviceId in SENDER_DEVICE_ID_POOL159) {
            for (filter in SenderKeyTargetFilter.entries) {
                check(listOf(target("w", "FAILED", deviceId)), filter)
            }
        }
    }
}
