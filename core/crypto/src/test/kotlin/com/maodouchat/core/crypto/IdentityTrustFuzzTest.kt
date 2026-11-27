package com.maodouchat.core.crypto

import com.maodouchat.core.model.IdentityTrustState
import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.InvalidMessageException

// ---------- oracle ----------

// Identity trust state machine restated by hand. Ground truth, read
// from IdentityTrustService.kt / SignalProtocolModels.kt:
//   - onVerification: any state converges to VERIFIED.
//   - onIdentityKeyChange: VERIFIED degrades to CHANGED, others stay.
//   - onReset: always UNKNOWN.
//   - outcomeFor: InvalidMessageException or IllegalArgumentException
//     (including subclasses, e.g. NumberFormatException) -> Skipped;
//     every other Throwable -> Failed.
internal fun trustVerifyOracle182(@Suppress("UNUSED_PARAMETER") state: String): String = "VERIFIED"

internal fun trustKeyChangeOracle182(state: String): String =
    if (state == "VERIFIED") "CHANGED" else state

internal fun trustResetOracle182(): String = "UNKNOWN"

internal fun trustApplyOracle182(start: String, steps: List<String>): String {
    var state = start
    for (step in steps) {
        state = when (step) {
            "V" -> trustVerifyOracle182(state)
            "K" -> trustKeyChangeOracle182(state)
            "R" -> trustResetOracle182()
            else -> error("unknown step $step")
        }
    }
    return state
}

internal fun distFailOracle182(kind: String): String = when (kind) {
    "IME", "IAE", "NFE", "IME_SUB" -> "Skipped"
    "ISE", "IOE", "NPE", "RTE", "EXC", "ERR" -> "Failed"
    else -> error("unknown kind $kind")
}

internal fun escJsonTrust182(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
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

// A subclass of InvalidMessageException: `is` checks must still see it.
internal class FakeInvalidMessage182 : InvalidMessageException()

// ---------- production ----------

internal fun trustApplyProduction182(start: String, steps: List<String>): String {
    var state = IdentityTrustState.valueOf(start)
    for (step in steps) {
        state = when (step) {
            "V" -> IdentityTrustStateMachine.onVerification(state)
            "K" -> IdentityTrustStateMachine.onIdentityKeyChange(state)
            "R" -> IdentityTrustStateMachine.onReset()
            else -> error("unknown step $step")
        }
    }
    return state.name
}

internal fun distFailError182(kind: String): Throwable = when (kind) {
    "IME" -> InvalidMessageException("bad message")
    "IAE" -> IllegalArgumentException("bad argument")
    "NFE" -> NumberFormatException("not a number")
    "IME_SUB" -> FakeInvalidMessage182()
    "ISE" -> IllegalStateException("bad state")
    "IOE" -> IOException("io failure")
    "NPE" -> NullPointerException("null")
    "RTE" -> RuntimeException("runtime")
    "EXC" -> Exception("generic")
    "ERR" -> Error("fatal")
    else -> error("unknown kind $kind")
}

internal fun distFailProduction182(kind: String): String =
    SenderKeyDistributionFailurePolicy.outcomeFor(distFailError182(kind)).name

// ---------- generators ----------

internal data class TrustSeqCase182(val start: String, val steps: List<String>)

internal data class DistFailCase182(val kind: String)

internal val TRUST_STATES182: List<String> = listOf("UNKNOWN", "TRUSTED", "VERIFIED", "CHANGED")

internal val TRUST_STEPS182: List<String> = listOf("V", "K", "R")

internal val DIST_FAIL_KINDS182: List<String> = listOf(
    "IME", "IAE", "NFE", "IME_SUB", "ISE", "IOE", "NPE", "RTE", "EXC", "ERR",
)

internal fun randomTrustSeqCase182(rnd: Random): TrustSeqCase182 {
    val start = TRUST_STATES182[rnd.nextInt(TRUST_STATES182.size)]
    val n = rnd.nextInt(0, 9)
    val steps = ArrayList<String>()
    repeat(n) { steps.add(TRUST_STEPS182[rnd.nextInt(TRUST_STEPS182.size)]) }
    return TrustSeqCase182(start, steps)
}

internal fun randomDistFailCase182(rnd: Random): DistFailCase182 =
    DistFailCase182(DIST_FAIL_KINDS182[rnd.nextInt(DIST_FAIL_KINDS182.size)])

internal fun trustSeqCaseWireJson182(c: TrustSeqCase182): String {
    val steps = c.steps.joinToString(",") { escJsonTrust182(it) }
    return "{\"start\":${escJsonTrust182(c.start)},\"steps\":[$steps]}"
}

internal fun distFailCaseWireJson182(c: DistFailCase182): String =
    "{\"kind\":${escJsonTrust182(c.kind)}}"

internal val TRUST_PINNED182: List<TrustSeqCase182> = listOf(
    TrustSeqCase182("UNKNOWN", listOf()),
    TrustSeqCase182("UNKNOWN", listOf("V")),
    TrustSeqCase182("UNKNOWN", listOf("K")),
    TrustSeqCase182("UNKNOWN", listOf("R")),
    TrustSeqCase182("TRUSTED", listOf("V")),
    TrustSeqCase182("TRUSTED", listOf("K")),
    TrustSeqCase182("TRUSTED", listOf("R")),
    TrustSeqCase182("VERIFIED", listOf("V")),
    TrustSeqCase182("VERIFIED", listOf("K")),
    TrustSeqCase182("VERIFIED", listOf("K", "V")),
    TrustSeqCase182("VERIFIED", listOf("K", "K")),
    TrustSeqCase182("VERIFIED", listOf("R")),
    TrustSeqCase182("CHANGED", listOf("V")),
    TrustSeqCase182("CHANGED", listOf("K")),
    TrustSeqCase182("CHANGED", listOf("R")),
    TrustSeqCase182("VERIFIED", listOf("K", "R", "V", "K")),
)

internal val DIST_FAIL_PINNED182: List<DistFailCase182> =
    DIST_FAIL_KINDS182.map { DistFailCase182(it) }

class IdentityTrustFuzzTest {

    private fun checkTrustSeq182(c: TrustSeqCase182) {
        assertEquals(
            "trustSeq($c)",
            trustApplyOracle182(c.start, c.steps),
            trustApplyProduction182(c.start, c.steps),
        )
    }

    private fun checkDistFail182(c: DistFailCase182) {
        assertEquals(
            "distFail($c)",
            distFailOracle182(c.kind),
            distFailProduction182(c.kind),
        )
    }

    @Test
    fun pinnedTrustTransitions() {
        for (c in TRUST_PINNED182) checkTrustSeq182(c)
        for (c in DIST_FAIL_PINNED182) checkDistFail182(c)
    }

    @Test
    fun randomTrustMatchesProduction() {
        val rnd = Random(182003)
        repeat(1500) { checkTrustSeq182(randomTrustSeqCase182(rnd)) }
        repeat(1500) { checkDistFail182(randomDistFailCase182(rnd)) }
    }

    @Test
    fun sweepTrustBoundaries() {
        // Every step sequence up to length 3 from every start state.
        for (start in TRUST_STATES182) {
            checkTrustSeq182(TrustSeqCase182(start, listOf()))
            for (a in TRUST_STEPS182) {
                checkTrustSeq182(TrustSeqCase182(start, listOf(a)))
                for (b in TRUST_STEPS182) {
                    checkTrustSeq182(TrustSeqCase182(start, listOf(a, b)))
                    for (d in TRUST_STEPS182) {
                        checkTrustSeq182(TrustSeqCase182(start, listOf(a, b, d)))
                    }
                }
            }
        }
        // Key-change / verify / reset interleavings from VERIFIED.
        checkTrustSeq182(TrustSeqCase182("VERIFIED", listOf("K", "V", "K", "R", "V", "K")))
    }
}
