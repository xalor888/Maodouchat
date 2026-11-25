// Fuzz / random-oracle tests for MultiRecipientCoveragePolicy
// transient failures (checklist line 931, 151st block). Test-only,
// zero production changes. The oracle is hand-written from the
// production source: trim/normalize + set coverage (same model as
// block 127, which covered the non-exception functions and left
// isTransient / transientFailure* explicitly uncovered), then the
// first transient failure in missing order. isTransient walks at
// most 6 cause links and returns at the first typed node, it never
// walks past a SignalExchangeException / ApiException verdict.
package com.maodouchat.core.crypto

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.Target
import com.maodouchat.core.model.TransientCoverageException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

internal data class ErrA151(
    val kind: String, // api | io | socket | signal | runtime | transient
    val apiKind: String? = null,
    val status: Int? = null,
    val sigFailure: String? = null,
    val msg: String? = null,
    val cause: ErrA151? = null,
)

// ---------- oracle ----------

// The probed Kotlin whitespace set (28 UTF-16 code units). Hand-written,
// never calls Char.isWhitespace / String.isBlank(). trim() strips this
// same set (probed locally with kotlinc 2.4.0: NBSP trims, NEL does not).
internal val BLANK_UNITS_ORACLE151 = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

internal fun isBlankOracle151(s: String): Boolean = s.all { it.code in BLANK_UNITS_ORACLE151 }

internal fun trimOracle151(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].code in BLANK_UNITS_ORACLE151) start++
    while (end > start && s[end - 1].code in BLANK_UNITS_ORACLE151) end--
    return s.substring(start, end)
}

// Error descriptor: the shape the oracle reasons about. The CI test
// builds a real Throwable from it (buildErrA151, below the class);
// the differential probe does the same from the wire JSON.

internal fun isTransientHttpStatusOracle151(status: Int?): Boolean =
    status == 408 || status == 429 || (status != null && status in 500..599)

internal fun isTransientOracle151(e: ErrA151?): Boolean {
    var cur = e
    repeat(6) {
        when (cur?.kind) {
            "signal" -> return when (cur.sigFailure) {
                "NETWORK", "TIMEOUT" -> true
                "HTTP" -> isTransientHttpStatusOracle151(cur.status)
                else -> false
            }
            "api" -> return when (cur.apiKind) {
                "NETWORK", "TIMEOUT" -> true
                "HTTP" -> isTransientHttpStatusOracle151(cur.status)
                else -> false
            }
            "io", "socket", "transient" -> return true
        }
        cur = cur?.cause
    }
    return false
}

internal fun missingRecipientsOracle151(req: List<String>, targets: List<Target>): List<String> {
    val required = LinkedHashSet<String>()
    for (id in req) {
        val t = trimOracle151(id)
        if (!isBlankOracle151(t)) required.add(t)
    }
    val covered = HashSet<String>()
    for (t in targets) {
        val u = trimOracle151(t.userId)
        if (!isBlankOracle151(u)) covered.add(u)
    }
    return required.filter { it !in covered }
}

internal fun transientRecipientsOracle151(
    req: List<String>,
    targets: List<Target>,
    failures: Map<String, ErrA151>,
): String? {
    val normalized = LinkedHashMap<String, ErrA151>()
    for ((k, v) in failures) normalized[trimOracle151(k)] = v
    for (id in missingRecipientsOracle151(req, targets)) {
        val err = normalized[id] ?: continue
        if (isTransientOracle151(err)) return id
    }
    return null
}

internal fun normalizeTargetOracle151(userId: String, deviceId: Int): Target? {
    val u = trimOracle151(userId)
    if (isBlankOracle151(u)) return null
    if (deviceId <= 0) return null
    return Target(u, deviceId)
}

internal fun missingTargetsOracle151(req: List<Target>, targets: List<Target>): List<Target> {
    val required = LinkedHashSet<Target>()
    for (t in req) normalizeTargetOracle151(t.userId, t.deviceId)?.let { required.add(it) }
    val covered = HashSet<Target>()
    for (t in targets) normalizeTargetOracle151(t.userId, t.deviceId)?.let { covered.add(it) }
    return required.filter { it !in covered }
}

internal fun transientTargetsOracle151(
    req: List<Target>,
    targets: List<Target>,
    failures: Map<Target, ErrA151>,
): Target? {
    val normalized = LinkedHashMap<Target, ErrA151>()
    for ((k, v) in failures) {
        val n = normalizeTargetOracle151(k.userId, k.deviceId) ?: continue
        normalized[n] = v
    }
    for (t in missingTargetsOracle151(req, targets)) {
        val err = normalized[t] ?: continue
        if (isTransientOracle151(err)) return t
    }
    return null
}

// ---------- generators ----------

private val ID_POOL151 = arrayOf(
    "", " ", "a", "a ", " a", "a b", "u1", "u2", "u1 ", "U1",
    "user-1", "é", "x", "\u00A0a\u00A0", "\t", "  ", "a\u00A0",
)

private val DEV_POOL151 = intArrayOf(1, 2, 3, 255, 0, -1, 256, 1000)

private val STATUS_POOL151 = arrayOf<Int?>(null, 200, 400, 404, 408, 409, 410, 429, 500, 502, 599, 600, -1)

private val API_KIND_POOL151 = arrayOf("HTTP", "TIMEOUT", "NETWORK", "INVALID_RESPONSE", "UNEXPECTED")

private val SIG_POOL151 = arrayOf(
    "SIGNED_PREKEY_MISSING", "HTTP", "EMPTY_RESPONSE", "TIMEOUT",
    "NETWORK", "INVALID_RESPONSE", "UNEXPECTED",
)

private val MSG_POOL151 = arrayOf<String?>(null, "", "x", "timeout", "network down", "  ", "boom")

internal val WS_CHARS151 = charArrayOf(
    ' ', '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F',
    '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
    '\u2008', '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

internal val TEXT_RANGES151 = arrayOf(
    0x21 to 0x7E, 0xA0 to 0x2FF, 0x300 to 0x2FFF, 0x4E00 to 0x9FFF, 0x1F300 to 0x1FAFF,
)

internal fun randomCodePoint151(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES151[rnd.nextInt(TEXT_RANGES151.size)]
    return rnd.nextInt(lo, hi + 1)
}

internal fun randomUnit151(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS151[rnd.nextInt(WS_CHARS151.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint151(rnd)))
    }
}

internal fun randomText151(rnd: Random, maxLen: Int = 12): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit151(rnd, sb) }
    return sb.toString()
}

internal fun randomId151(rnd: Random): String =
    if (rnd.nextInt(10) < 7) ID_POOL151[rnd.nextInt(ID_POOL151.size)] else randomText151(rnd)

internal fun randomTarget151(rnd: Random): Target =
    Target(randomId151(rnd), if (rnd.nextInt(10) < 7) DEV_POOL151[rnd.nextInt(DEV_POOL151.size)] else rnd.nextInt(-5, 301))

internal fun randomErrA151(rnd: Random, depth: Int = 0): ErrA151 {
    val kind = when (rnd.nextInt(100)) {
        in 0 until 35 -> "api"
        in 35 until 45 -> "io"
        in 45 until 50 -> "socket"
        in 50 until 75 -> "signal"
        in 75 until 90 -> "runtime"
        else -> "transient"
    }
    val cause = if (depth < 2 && rnd.nextInt(100) < 35) randomErrA151(rnd, depth + 1) else null
    return when (kind) {
        "api" -> ErrA151(kind, API_KIND_POOL151[rnd.nextInt(API_KIND_POOL151.size)], STATUS_POOL151[rnd.nextInt(STATUS_POOL151.size)], msg = MSG_POOL151[rnd.nextInt(MSG_POOL151.size)], cause = cause)
        "signal" -> ErrA151(kind, status = STATUS_POOL151[rnd.nextInt(STATUS_POOL151.size)], sigFailure = SIG_POOL151[rnd.nextInt(SIG_POOL151.size)], msg = MSG_POOL151[rnd.nextInt(MSG_POOL151.size)], cause = cause)
        else -> ErrA151(kind, msg = MSG_POOL151[rnd.nextInt(MSG_POOL151.size)], cause = cause)
    }
}

internal fun randomIdList151(rnd: Random): List<String> =
    List(rnd.nextInt(0, 5)) { randomId151(rnd) }

internal fun randomTargetList151(rnd: Random): List<Target> =
    List(rnd.nextInt(0, 5)) { randomTarget151(rnd) }

internal fun randomRecipFailures151(rnd: Random): Map<String, ErrA151> {
    val out = LinkedHashMap<String, ErrA151>()
    repeat(rnd.nextInt(0, 4)) { out[randomId151(rnd)] = randomErrA151(rnd) }
    return out
}

internal fun randomTargetFailures151(rnd: Random): Map<Target, ErrA151> {
    val out = LinkedHashMap<Target, ErrA151>()
    repeat(rnd.nextInt(0, 4)) { out[randomTarget151(rnd)] = randomErrA151(rnd) }
    return out
}

internal fun errJson151(e: ErrA151): JSONObject {
    val o = JSONObject()
    o.put("k", e.kind)
    e.apiKind?.let { o.put("ak", it) }
    e.status?.let { o.put("st", it) }
    e.sigFailure?.let { o.put("sf", it) }
    e.msg?.let { o.put("m", it) }
    e.cause?.let { o.put("c", errJson151(it)) }
    return o
}

internal fun wireMrcRecip151(req: List<String>, targets: List<Target>, failures: Map<String, ErrA151>): String {
    val root = JSONObject()
    root.put("req", JSONArray(req))
    val tarr = JSONArray()
    targets.forEach { tarr.put(JSONObject().put("u", it.userId).put("d", it.deviceId)) }
    root.put("targets", tarr)
    val farr = JSONArray()
    failures.forEach { (k, v) -> farr.put(JSONArray().put(k).put(errJson151(v))) }
    root.put("failures", farr)
    return root.toString()
}

internal fun wireMrcTarget151(req: List<Target>, targets: List<Target>, failures: Map<Target, ErrA151>): String {
    val root = JSONObject()
    fun tarr(list: List<Target>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.userId).put("d", it.deviceId)) }
        return a
    }
    root.put("req", tarr(req))
    root.put("targets", tarr(targets))
    val farr = JSONArray()
    failures.forEach { (k, v) -> farr.put(JSONArray().put(JSONObject().put("u", k.userId).put("d", k.deviceId)).put(errJson151(v))) }
    root.put("failures", farr)
    return root.toString()
}

internal fun wireErr151(e: ErrA151): String = errJson151(e).toString()

class MultiRecipientCoverageTransientFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val transientApi = ErrA151("api", "NETWORK")
        val deadApi = ErrA151("api", "HTTP", 200)
        // Empty required set: nothing can be missing.
        assertNull(transientRecipientsOracle151(emptyList(), emptyList(), mapOf("u1" to transientApi)))
        assertEquals(
            transientRecipientsOracle151(emptyList(), emptyList(), mapOf("u1" to transientApi)),
            prodRecip(emptyList(), emptyList(), mapOf("u1" to transientApi)),
        )
        // Covered recipient: no failure surfaces.
        assertEquals(
            transientRecipientsOracle151(listOf("u1"), listOf(Target("u1", 1)), mapOf("u1" to transientApi)),
            prodRecip(listOf("u1"), listOf(Target("u1", 1)), mapOf("u1" to transientApi)),
        )
        // Missing + transient: the missing id comes back.
        assertEquals(
            "u1",
            transientRecipientsOracle151(listOf("u1"), emptyList(), mapOf("u1" to transientApi)),
        )
        assertEquals(
            transientRecipientsOracle151(listOf("u1"), emptyList(), mapOf("u1" to transientApi)),
            prodRecip(listOf("u1"), emptyList(), mapOf("u1" to transientApi)),
        )
        // Missing but dead failure: null.
        assertNull(transientRecipientsOracle151(listOf("u1"), emptyList(), mapOf("u1" to deadApi)))
        assertEquals(
            transientRecipientsOracle151(listOf("u1"), emptyList(), mapOf("u1" to deadApi)),
            prodRecip(listOf("u1"), emptyList(), mapOf("u1" to deadApi)),
        )
        // Order: first missing id with a transient failure wins, even when
        // an earlier missing id has a dead failure.
        val req = listOf("u1", "u2", "u3")
        val failures = mapOf("u1" to deadApi, "u2" to transientApi, "u3" to transientApi)
        assertEquals("u2", transientRecipientsOracle151(req, emptyList(), failures))
        assertEquals(
            transientRecipientsOracle151(req, emptyList(), failures),
            prodRecip(req, emptyList(), failures),
        )
        // Trim collision: " u1 " and "u1" normalize to one key, last wins.
        val colliding = mapOf("u1" to transientApi, " u1 " to deadApi)
        assertNull(transientRecipientsOracle151(listOf("u1"), emptyList(), colliding))
        assertEquals(
            transientRecipientsOracle151(listOf("u1"), emptyList(), colliding),
            prodRecip(listOf("u1"), emptyList(), colliding),
        )
        // isTransient pins: typed verdicts never walk past the typed node.
        assertTrue(isTransientOracle151(ErrA151("signal", sigFailure = "NETWORK")))
        assertTrue(isTransientOracle151(ErrA151("signal", sigFailure = "HTTP", status = 429)))
        assertEquals(false, isTransientOracle151(ErrA151("signal", sigFailure = "HTTP", status = 200)))
        // EMPTY_RESPONSE returns false even with a transient cause behind it.
        assertEquals(false, isTransientOracle151(ErrA151("signal", sigFailure = "EMPTY_RESPONSE", cause = transientApi)))
        assertEquals(false, isTransientOracle151(ErrA151("api", "INVALID_RESPONSE", cause = transientApi)))
        // Untyped wrapper walks to its cause.
        assertTrue(isTransientOracle151(ErrA151("runtime", cause = ErrA151("io"))))
        assertEquals(false, isTransientOracle151(ErrA151("runtime", cause = deadApi)))
        // Depth: 5 wrappers + io at depth 5 is seen; io at depth 6 is not.
        fun chain(depth: Int): ErrA151 =
            if (depth == 0) ErrA151("io") else ErrA151("runtime", cause = chain(depth - 1))
        assertTrue(isTransientOracle151(chain(5)))
        assertEquals(false, isTransientOracle151(chain(6)))
        assertEquals(
            isTransientOracle151(chain(5)),
            MultiRecipientCoveragePolicy.isTransient(buildErrA151(chain(5))),
        )
        assertEquals(
            isTransientOracle151(chain(6)),
            MultiRecipientCoveragePolicy.isTransient(buildErrA151(chain(6))),
        )
        // Targets: device 0 / negative never normalize, so never missing.
        assertNull(transientTargetsOracle151(listOf(Target("u1", 0)), emptyList(), mapOf(Target("u1", 0) to transientApi)))
        assertEquals(
            transientTargetsOracle151(listOf(Target("u1", 0)), emptyList(), mapOf(Target("u1", 0) to transientApi)),
            prodTarget(listOf(Target("u1", 0)), emptyList(), mapOf(Target("u1", 0) to transientApi)),
        )
        // Targets: trimmed user id matches the covered target.
        assertEquals(
            transientTargetsOracle151(listOf(Target(" u1 ", 2)), listOf(Target("u1", 2)), mapOf(Target("u1", 2) to transientApi)),
            prodTarget(listOf(Target(" u1 ", 2)), listOf(Target("u1", 2)), mapOf(Target("u1", 2) to transientApi)),
        )
        // Targets: missing device surfaces its transient failure.
        assertEquals(
            Target("u1", 2),
            transientTargetsOracle151(listOf(Target("u1", 2)), listOf(Target("u1", 1)), mapOf(Target("u1", 2) to transientApi)),
        )
        assertEquals(
            transientTargetsOracle151(listOf(Target("u1", 2)), listOf(Target("u1", 1)), mapOf(Target("u1", 2) to transientApi)),
            prodTarget(listOf(Target("u1", 2)), listOf(Target("u1", 1)), mapOf(Target("u1", 2) to transientApi)),
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(151001)
        repeat(1200) {
            // Recipient variant.
            val req = randomIdList151(rnd)
            val targets = randomTargetList151(rnd)
            val failures = randomRecipFailures151(rnd)
            assertEquals(
                transientRecipientsOracle151(req, targets, failures),
                prodRecip(req, targets, failures),
            )
            // Target variant.
            val reqT = randomTargetList151(rnd)
            val targetsT = randomTargetList151(rnd)
            val failuresT = randomTargetFailures151(rnd)
            assertEquals(
                transientTargetsOracle151(reqT, targetsT, failuresT),
                prodTarget(reqT, targetsT, failuresT),
            )
            // Bare isTransient on a random chain.
            val err = randomErrA151(rnd)
            assertEquals(
                isTransientOracle151(err),
                MultiRecipientCoveragePolicy.isTransient(buildErrA151(err)),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // apiKind x status grid for isTransient.
        for (kind in API_KIND_POOL151) {
            for (status in STATUS_POOL151) {
                val err = ErrA151("api", kind, status)
                assertEquals(
                    isTransientOracle151(err),
                    MultiRecipientCoveragePolicy.isTransient(buildErrA151(err)),
                )
            }
        }
        // sigFailure x status grid for isTransient.
        for (failure in SIG_POOL151) {
            for (status in STATUS_POOL151) {
                val err = ErrA151("signal", status = status, sigFailure = failure)
                assertEquals(
                    isTransientOracle151(err),
                    MultiRecipientCoveragePolicy.isTransient(buildErrA151(err)),
                )
            }
        }
        // Chain-depth sweep: io at depth d is seen iff d < 6.
        for (d in 0..7) {
            var err = ErrA151("io")
            repeat(d) { err = ErrA151("runtime", cause = err) }
            assertEquals(
                isTransientOracle151(err),
                MultiRecipientCoveragePolicy.isTransient(buildErrA151(err)),
            )
        }
        // Recipient id products over a small pool (order + trim + dedup).
        val ids = listOf("u1", "u2", " u1 ", "", " ")
        for (a in ids) {
            for (b in ids) {
                val req = listOf(a, b)
                val failures = mapOf(
                    "u1" to ErrA151("api", "NETWORK"),
                    "u2" to ErrA151("api", "HTTP", 200),
                )
                assertEquals(
                    transientRecipientsOracle151(req, emptyList(), failures),
                    prodRecip(req, emptyList(), failures),
                )
                assertEquals(
                    transientRecipientsOracle151(req, listOf(Target("u1", 1)), failures),
                    prodRecip(req, listOf(Target("u1", 1)), failures),
                )
            }
        }
        // Target device grid: required device x covered device.
        for (dev in DEV_POOL151) {
            for (covered in DEV_POOL151) {
                val req = listOf(Target("u1", dev))
                val targets = listOf(Target("u1", covered))
                val failures = mapOf(Target("u1", dev) to ErrA151("api", "TIMEOUT"))
                assertEquals(
                    transientTargetsOracle151(req, targets, failures),
                    prodTarget(req, targets, failures),
                )
            }
        }
    }

    // Production wrappers: resolve the production result back to the
    // oracle's answer (matched id / target, or null). The cause of the
    // returned TransientCoverageException is the built Throwable itself,
    // so identity (===) maps it back to the descriptor key.
    private fun prodRecip(
        req: List<String>,
        targets: List<Target>,
        failures: Map<String, ErrA151>,
    ): String? {
        val built = failures.mapValues { buildErrA151(it.value) }
        val prod = MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(req, targets, built)
        val cause = prod?.cause ?: return null
        return built.entries.firstOrNull { it.value === cause }?.key?.let(::trimOracle151)
    }

    private fun prodTarget(
        req: List<Target>,
        targets: List<Target>,
        failures: Map<Target, ErrA151>,
    ): Target? {
        val built = failures.mapValues { buildErrA151(it.value) }
        val prod = MultiRecipientCoveragePolicy.transientFailureForMissingTargets(req, targets, built)
        val cause = prod?.cause ?: return null
        return built.entries.firstOrNull { it.value === cause }?.key?.let {
            normalizeTargetOracle151(it.userId, it.deviceId)
        }
    }
}

// Test-only Throwable builder: turns a descriptor into the real
// exception the production code classifies. Never called by the oracle.
internal fun buildErrA151(e: ErrA151): Throwable {
    val cause = e.cause?.let(::buildErrA151)
    return when (e.kind) {
        "api" -> ApiException(ApiFailureKind.valueOf(e.apiKind!!), e.status, e.msg, cause = cause)
        "io" -> if (cause == null) java.io.IOException(e.msg) else java.io.IOException(e.msg, cause)
        "socket" -> java.net.SocketTimeoutException(e.msg).apply { if (cause != null) initCause(cause) }
        "signal" -> SignalExchangeException(SignalExchangeFailure.valueOf(e.sigFailure!!), e.status, cause = cause)
        "transient" -> TransientCoverageException(e.msg ?: "t", cause)
        else -> if (cause == null) RuntimeException(e.msg) else RuntimeException(e.msg, cause)
    }
}
