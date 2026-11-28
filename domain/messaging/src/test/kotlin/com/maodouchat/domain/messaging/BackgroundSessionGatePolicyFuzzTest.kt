package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// mayContinue reimplemented from the production branches: purge wins,
// then a blank expected id, then a null/blank token or live id, then
// exact (untrimmed, case-sensitive) equality of live id vs expected.
internal fun mayContinueOracle165(
    expectedUserId: String,
    liveToken: String?,
    liveUserId: String?,
    purgeInProgress: Boolean
): Boolean {
    if (purgeInProgress) return false
    if (expectedUserId.isBlank()) return false
    if (liveToken.isNullOrBlank() || liveUserId.isNullOrBlank()) return false
    return liveUserId == expectedUserId
}

// ---------- generators ----------

internal val GATE_POOL165: List<String?> = listOf(
    null, "", " ", "  ", "\t", "\n", "\r", "\u000b", "\u000c",
    "\u001c", "\u001d", "\u001e", "\u001f",
    "\u00a0", "\u1680", "\u2000", "\u2003", "\u2007", "\u2028", "\u2029",
    "\u202f", "\u205f", "\u3000",
    "\u0085", "\ufeff", "\u200b",
    "u1", "u2", "me", "a", "ab", "0", "_",
    " u1", "u1 ", " u1 ", "\tu1", "u1\t", "\u00a0u1", "u1\u00a0",
    "U1", "u1\u2003", "\u2003u1",
)

internal const val GATE_ALPHABET165 = "ab \t\n\u00a0\u2003\u001c_"

internal fun randomGateString165(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(GATE_ALPHABET165[rnd.nextInt(GATE_ALPHABET165.length)]) }
    return sb.toString()
}

internal data class GateCase165(
    val expected: String,
    val token: String?,
    val live: String?,
    val purge: Boolean
)

internal fun randomGateCase165(rnd: Random): GateCase165 {
    fun pick(): String? {
        return when (rnd.nextInt(10)) {
            0 -> null
            1 -> GATE_POOL165[rnd.nextInt(GATE_POOL165.size)]
            2 -> {
                val base = listOf("u1", "u2", "me", "a")[rnd.nextInt(4)]
                val sb = StringBuilder()
                for (ch in base) sb.append(if (rnd.nextBoolean()) ch.uppercaseChar() else ch)
                sb.toString()
            }
            else -> randomGateString165(rnd)
        }
    }
    val expected = pick() ?: "u1"
    return GateCase165(expected, pick(), pick(), rnd.nextInt(10) == 0)
}

internal fun gateWireJson165(c: GateCase165): String {
    fun esc(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder()
        sb.append("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        val v = ch.code
                        sb.append("0123456789abcdef"[v shr 12])
                        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                        sb.append("0123456789abcdef"[v and 0xF])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
    return "{\"expected\":${esc(c.expected)},\"token\":${esc(c.token)}," +
        "\"live\":${esc(c.live)},\"purge\":${c.purge}}"
}

class BackgroundSessionGatePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            GateCase165("u1", "t", "u1", false) to true,
            GateCase165("u1", "t", "u2", false) to false,
            GateCase165("u1", "t", "u1", true) to false,
            GateCase165("u1", "t", "u2", true) to false,
            GateCase165("", "t", "", false) to false,
            GateCase165(" ", "t", " ", false) to false,
            GateCase165("\u00a0", "t", "\u00a0", false) to false,
            GateCase165("\u001c", "t", "\u001c", false) to false,
            GateCase165("\u2003", "t", "\u2003", false) to false,
            GateCase165("\u0085", "t", "\u0085", false) to true,
            GateCase165("\ufeff", "t", "\ufeff", false) to true,
            GateCase165("\u200b", "t", "\u200b", false) to true,
            GateCase165("u1", null, "u1", false) to false,
            GateCase165("u1", "", "u1", false) to false,
            GateCase165("u1", " ", "u1", false) to false,
            GateCase165("u1", "\u00a0", "u1", false) to false,
            GateCase165("u1", "t", null, false) to false,
            GateCase165("u1", "t", "", false) to false,
            GateCase165("u1", "t", " ", false) to false,
            GateCase165("u1", "t", "\u00a0", false) to false,
            GateCase165(" u1", "t", " u1", false) to true,
            GateCase165(" u1", "t", "u1", false) to false,
            GateCase165("u1", "t", "u1 ", false) to false,
            GateCase165("U1", "t", "u1", false) to false,
            GateCase165("u1", "t", "U1", false) to false,
            GateCase165("", null, null, true) to false,
            GateCase165("u1", "t", "u1", false) to true,
        )
        for ((c, expected) in cases) {
            val got = BackgroundSessionGatePolicy.mayContinue(c.expected, c.token, c.live, c.purge)
            assertEquals(expected, got, "mayContinue($c)")
            assertEquals(expected, mayContinueOracle165(c.expected, c.token, c.live, c.purge), "oracle($c)")
        }
        for (expected in GATE_POOL165) {
            if (expected == null) continue
            for (token in GATE_POOL165) {
                for (live in GATE_POOL165) {
                    for (purge in listOf(false, true)) {
                        val got = BackgroundSessionGatePolicy.mayContinue(expected, token, live, purge)
                        val want = mayContinueOracle165(expected, token, live, purge)
                        assertEquals(want, got, "mayContinue($expected,$token,$live,$purge)")
                    }
                }
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(165002)
        repeat(4000) {
            val c = randomGateCase165(rnd)
            assertEquals(
                mayContinueOracle165(c.expected, c.token, c.live, c.purge),
                BackgroundSessionGatePolicy.mayContinue(c.expected, c.token, c.live, c.purge),
                "mayContinue($c)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        fun sweepStrings(prefix: String, depth: Int, out: MutableList<String>) {
            if (depth == 0) {
                out.add(prefix)
                return
            }
            sweepStrings(prefix, depth - 1, out)
            for (ch in GATE_ALPHABET165) sweepStrings(prefix + ch, depth - 1, out)
        }
        val strings = ArrayList<String>()
        sweepStrings("", 4, strings)
        for (s in strings) {
            assertEquals(
                mayContinueOracle165(s, "t", s, false),
                BackgroundSessionGatePolicy.mayContinue(s, "t", s, false),
                "mayContinue(expected=$s)"
            )
            assertEquals(
                mayContinueOracle165("u1", s, "u1", false),
                BackgroundSessionGatePolicy.mayContinue("u1", s, "u1", false),
                "mayContinue(token=$s)"
            )
            assertEquals(
                mayContinueOracle165("u1", "t", s, false),
                BackgroundSessionGatePolicy.mayContinue("u1", "t", s, false),
                "mayContinue(live=$s)"
            )
        }
        for (expected in GATE_POOL165) {
            if (expected == null) continue
            for (token in GATE_POOL165) {
                for (live in GATE_POOL165) {
                    assertEquals(
                        mayContinueOracle165(expected, token, live, false),
                        BackgroundSessionGatePolicy.mayContinue(expected, token, live, false),
                        "mayContinue($expected,$token,$live)"
                    )
                }
            }
        }
    }
}
