package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------- oracle ----------

// DecryptRetryTracker restated by hand. Ground truth, read from
// the decrypt-failure policy file:
//   - disposition: Success/Duplicate/UnsupportedEnvelope/
//     NotForThisDevice stop; NoSession/UntrustedIdentity/FutureEpoch/
//     Failed retry.
//   - shouldAcknowledge: blank id falls back to the disposition; a
//     STOP result clears the counter and acks; a RETRY result counts
//     up and acks once the count reaches maxAttempts.
//   - recordCryptoFailure counts one failure (blank id ignored);
//     the two-arg form also records sender ownership.
//   - markTerminal moves a fingerprint to the terminal set (count
//     cleared); clear removes it everywhere; clearForSender clears
//     only one sender's fingerprints; clearAll empties everything.
//   - eviction (total attempts + terminal > maxTracked) drops the
//     oldest entries first in this oracle; production drops an
//     unspecified subset, so eviction is only asserted by count in
//     the pinned test, never through this oracle.
internal fun trackerDispositionOracle183(result: DecryptResult): Disposition = when (result) {
    is DecryptResult.Success,
    DecryptResult.Duplicate,
    DecryptResult.UnsupportedEnvelope,
    DecryptResult.NotForThisDevice -> Disposition.STOP
    DecryptResult.NoSession,
    DecryptResult.UntrustedIdentity,
    DecryptResult.FutureEpoch,
    DecryptResult.Failed -> Disposition.RETRY
}

internal class TrackerOracle183(
    // Literals, not DecryptFailurePolicy constants: the oracle must
    // not read production values (defaults are unused in practice;
    // applyOracle183 always passes explicit args).
    private val maxAttempts: Int = 5,
    private val maxTracked: Int = 256,
) {
    private val attempts = LinkedHashMap<String, Int>()
    private val fingerprintsBySender = LinkedHashMap<String, MutableSet<String>>()
    private val terminalFingerprints = LinkedHashSet<String>()

    fun shouldAcknowledge(envelopeId: String, result: DecryptResult): Boolean {
        if (envelopeId.isBlank()) {
            return trackerDispositionOracle183(result) == Disposition.STOP
        }
        if (trackerDispositionOracle183(result) == Disposition.STOP) {
            attempts.remove(envelopeId)
            return true
        }
        val next = (attempts[envelopeId] ?: 0) + 1
        if (next >= maxAttempts) {
            attempts.remove(envelopeId)
            evictIfNeeded()
            return true
        }
        attempts[envelopeId] = next
        evictIfNeeded()
        return false
    }

    fun failureCount(fingerprint: String): Int = attempts[fingerprint] ?: 0

    fun recordCryptoFailure(fingerprint: String) {
        if (fingerprint.isBlank()) return
        terminalFingerprints.remove(fingerprint)
        attempts[fingerprint] = (attempts[fingerprint] ?: 0) + 1
        evictIfNeeded()
    }

    fun recordCryptoFailure(senderId: String, fingerprint: String) {
        if (fingerprint.isBlank()) return
        recordCryptoFailure(fingerprint)
        if (senderId.isNotBlank()) {
            fingerprintsBySender.getOrPut(senderId) { LinkedHashSet() }.add(fingerprint)
        }
    }

    fun clear(fingerprint: String) {
        if (fingerprint.isBlank()) return
        attempts.remove(fingerprint)
        terminalFingerprints.remove(fingerprint)
        removeFingerprintOwnership(fingerprint)
    }

    fun markTerminal(fingerprint: String) {
        if (fingerprint.isBlank()) return
        attempts.remove(fingerprint)
        removeFingerprintOwnership(fingerprint)
        terminalFingerprints.add(fingerprint)
        evictIfNeeded()
    }

    fun isTerminal(fingerprint: String): Boolean =
        fingerprint.isNotBlank() && fingerprint in terminalFingerprints

    fun clearForSender(senderId: String) {
        if (senderId.isBlank()) return
        val fingerprints = fingerprintsBySender.remove(senderId).orEmpty().toList()
        fingerprints.forEach {
            attempts.remove(it)
            terminalFingerprints.remove(it)
        }
    }

    fun clearAll() {
        attempts.clear()
        fingerprintsBySender.clear()
        terminalFingerprints.clear()
    }

    private fun evictIfNeeded() {
        val total = attempts.size + terminalFingerprints.size
        if (total <= maxTracked) return
        var extra = total - maxTracked
        attempts.keys.take(extra).forEach {
            attempts.remove(it)
            removeFingerprintOwnership(it)
            extra--
        }
        if (extra > 0) {
            terminalFingerprints.take(extra).forEach {
                terminalFingerprints.remove(it)
                removeFingerprintOwnership(it)
            }
        }
    }

    private fun removeFingerprintOwnership(fingerprint: String) {
        // Snapshot first: removing an emptied sender inside the
        // loop would throw ConcurrentModificationException on a
        // LinkedHashMap (production uses ConcurrentHashMap).
        fingerprintsBySender.toList().forEach { (senderId, fingerprints) ->
            fingerprints.remove(fingerprint)
            if (fingerprints.isEmpty()) {
                fingerprintsBySender.remove(senderId)
            }
        }
    }
}

internal fun escJsonTracker183(s: String): String {
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

// One tracker step: op + args; result kinds are the 8 DecryptResult names.
internal data class TrackerStep183(
    val op: String,
    val id: String? = null,
    val sender: String? = null,
    val result: String? = null,
)

internal data class TrackerCase183(
    val maxAttempts: Int,
    val maxTracked: Int,
    val steps: List<TrackerStep183>,
)

internal fun resultOf183(kind: String): DecryptResult = when (kind) {
    "Success" -> DecryptResult.Success("p")
    "Duplicate" -> DecryptResult.Duplicate
    "UnsupportedEnvelope" -> DecryptResult.UnsupportedEnvelope
    "NotForThisDevice" -> DecryptResult.NotForThisDevice
    "NoSession" -> DecryptResult.NoSession
    "UntrustedIdentity" -> DecryptResult.UntrustedIdentity
    "FutureEpoch" -> DecryptResult.FutureEpoch
    "Failed" -> DecryptResult.Failed
    else -> error("unknown result $kind")
}

// Step outputs: Boolean/Int for queries, null for mutating steps.
internal fun applyOracle183(c: TrackerCase183): List<Any?> {
    val t = TrackerOracle183(c.maxAttempts, c.maxTracked)
    val out = ArrayList<Any?>()
    for (s in c.steps) {
        when (s.op) {
            "ack" -> out.add(t.shouldAcknowledge(s.id ?: "", resultOf183(s.result!!)))
            "count" -> out.add(t.failureCount(s.id ?: ""))
            "rec" -> { t.recordCryptoFailure(s.id ?: ""); out.add(null) }
            "rec2" -> { t.recordCryptoFailure(s.sender ?: "", s.id ?: ""); out.add(null) }
            "clear" -> { t.clear(s.id ?: ""); out.add(null) }
            "term" -> { t.markTerminal(s.id ?: ""); out.add(null) }
            "isterm" -> out.add(t.isTerminal(s.id ?: ""))
            "clearsender" -> { t.clearForSender(s.sender ?: ""); out.add(null) }
            "clearall" -> { t.clearAll(); out.add(null) }
            else -> error("unknown step ${s.op}")
        }
    }
    return out
}

internal fun trackerStepWireJson183(s: TrackerStep183): String {
    val id = s.id?.let { escJsonTracker183(it) } ?: "null"
    val sender = s.sender?.let { escJsonTracker183(it) } ?: "null"
    val result = s.result?.let { escJsonTracker183(it) } ?: "null"
    return "{\"op\":${escJsonTracker183(s.op)},\"id\":$id,\"sender\":$sender,\"result\":$result}"
}

internal fun trackerCaseWireJson183(c: TrackerCase183): String {
    val steps = c.steps.joinToString(",") { trackerStepWireJson183(it) }
    return "{\"maxAttempts\":${c.maxAttempts},\"maxTracked\":${c.maxTracked},\"steps\":[$steps]}"
}

// ---------- production ----------

internal fun applyProduction183(c: TrackerCase183): List<Any?> {
    val t = DecryptRetryTracker(c.maxAttempts, c.maxTracked)
    val out = ArrayList<Any?>()
    for (s in c.steps) {
        when (s.op) {
            "ack" -> out.add(t.shouldAcknowledge(s.id ?: "", resultOf183(s.result!!)))
            "count" -> out.add(t.failureCount(s.id ?: ""))
            "rec" -> { t.recordCryptoFailure(s.id ?: ""); out.add(null) }
            "rec2" -> { t.recordCryptoFailure(s.sender ?: "", s.id ?: ""); out.add(null) }
            "clear" -> { t.clear(s.id ?: ""); out.add(null) }
            "term" -> { t.markTerminal(s.id ?: ""); out.add(null) }
            "isterm" -> out.add(t.isTerminal(s.id ?: ""))
            "clearsender" -> { t.clearForSender(s.sender ?: ""); out.add(null) }
            "clearall" -> { t.clearAll(); out.add(null) }
            else -> error("unknown step ${s.op}")
        }
    }
    return out
}

// ---------- generators ----------

internal val TRACKER_RESULTS183: List<String> = listOf(
    "Success", "Duplicate", "UnsupportedEnvelope", "NotForThisDevice",
    "NoSession", "UntrustedIdentity", "FutureEpoch", "Failed",
)

internal val TRACKER_IDS183: List<String> = listOf(
    "f1", "f2", "f3", "env-1", "env-2", "", "  ", "fp-long-0123456789",
)

internal val TRACKER_SENDERS183: List<String> = listOf(
    "alice", "bob", "", "  ",
)

internal fun randomTrackerStep183(rnd: Random): TrackerStep183 {
    return when (rnd.nextInt(10)) {
        0 -> TrackerStep183("ack", TRACKER_IDS183.random(rnd), null, TRACKER_RESULTS183.random(rnd))
        1 -> TrackerStep183("count", TRACKER_IDS183.random(rnd))
        2 -> TrackerStep183("rec", TRACKER_IDS183.random(rnd))
        3 -> TrackerStep183("rec2", TRACKER_IDS183.random(rnd), TRACKER_SENDERS183.random(rnd))
        4 -> TrackerStep183("clear", TRACKER_IDS183.random(rnd))
        5 -> TrackerStep183("term", TRACKER_IDS183.random(rnd))
        6 -> TrackerStep183("isterm", TRACKER_IDS183.random(rnd))
        7 -> TrackerStep183("clearsender", null, TRACKER_SENDERS183.random(rnd))
        8 -> TrackerStep183("clearall")
        else -> TrackerStep183("ack", TRACKER_IDS183.random(rnd), null, TRACKER_RESULTS183.random(rnd))
    }
}

internal fun randomTrackerCase183(rnd: Random): TrackerCase183 {
    val n = rnd.nextInt(0, 21)
    return TrackerCase183(
        maxAttempts = listOf(1, 2, 3, 5).random(rnd),
        // Large cap: the differential legs never trigger eviction
        // (production eviction order is unspecified).
        maxTracked = 256,
        steps = List(n) { randomTrackerStep183(rnd) },
    )
}

class DecryptRetryTrackerFuzzTest {

    private fun checkTracker183(c: TrackerCase183) {
        assertEquals(
            applyOracle183(c),
            applyProduction183(c),
            "tracker($c)",
        )
    }

    @Test
    fun pinnedTrackerTransitions() {
        // Every result kind acks from a fresh tracker, blank and non-blank id.
        for (kind in TRACKER_RESULTS183) {
            checkTracker183(TrackerCase183(5, 256, listOf(TrackerStep183("ack", "e1", null, kind))))
            checkTracker183(TrackerCase183(5, 256, listOf(TrackerStep183("ack", "", null, kind))))
            checkTracker183(TrackerCase183(5, 256, listOf(TrackerStep183("ack", "  ", null, kind))))
        }
        // Retry counting reaches maxAttempts exactly, for 1/2/3/5.
        for (max in listOf(1, 2, 3, 5)) {
            val steps = ArrayList<TrackerStep183>()
            repeat(max - 1) { steps.add(TrackerStep183("ack", "e1", null, "Failed")) }
            steps.add(TrackerStep183("count", "e1"))
            steps.add(TrackerStep183("ack", "e1", null, "Failed"))
            steps.add(TrackerStep183("count", "e1"))
            checkTracker183(TrackerCase183(max, 256, steps))
        }
        // STOP results clear the counter; a later retry starts over.
        checkTracker183(
            TrackerCase183(
                5, 256,
                listOf(
                    TrackerStep183("ack", "e1", null, "Failed"),
                    TrackerStep183("ack", "e1", null, "Failed"),
                    TrackerStep183("count", "e1"),
                    TrackerStep183("ack", "e1", null, "Success"),
                    TrackerStep183("count", "e1"),
                    TrackerStep183("ack", "e1", null, "Failed"),
                    TrackerStep183("count", "e1"),
                ),
            )
        )
        // record/clear/markTerminal/isTerminal lifecycle.
        checkTracker183(
            TrackerCase183(
                5, 256,
                listOf(
                    TrackerStep183("rec", "fp1"),
                    TrackerStep183("rec", "fp1"),
                    TrackerStep183("count", "fp1"),
                    TrackerStep183("isterm", "fp1"),
                    TrackerStep183("term", "fp1"),
                    TrackerStep183("count", "fp1"),
                    TrackerStep183("isterm", "fp1"),
                    TrackerStep183("rec", "fp1"),
                    TrackerStep183("isterm", "fp1"),
                    TrackerStep183("count", "fp1"),
                    TrackerStep183("clear", "fp1"),
                    TrackerStep183("isterm", "fp1"),
                    TrackerStep183("count", "fp1"),
                ),
            )
        )
        // Sender ownership: clearForSender only clears that sender.
        checkTracker183(
            TrackerCase183(
                5, 256,
                listOf(
                    TrackerStep183("rec2", "fp1", "alice"),
                    TrackerStep183("rec2", "fp2", "alice"),
                    TrackerStep183("rec2", "fp3", "bob"),
                    TrackerStep183("clearsender", null, "alice"),
                    TrackerStep183("count", "fp1"),
                    TrackerStep183("count", "fp2"),
                    TrackerStep183("count", "fp3"),
                    TrackerStep183("clearsender", null, "bob"),
                    TrackerStep183("count", "fp3"),
                    TrackerStep183("clearsender", null, ""),
                    TrackerStep183("clearsender", null, "nobody"),
                ),
            )
        )
        // clearAll empties everything, including terminal marks.
        checkTracker183(
            TrackerCase183(
                5, 256,
                listOf(
                    TrackerStep183("rec", "fp1"),
                    TrackerStep183("term", "fp2"),
                    TrackerStep183("clearall"),
                    TrackerStep183("count", "fp1"),
                    TrackerStep183("isterm", "fp2"),
                ),
            )
        )
        // Blank ids are no-ops for record/clear/term.
        checkTracker183(
            TrackerCase183(
                5, 256,
                listOf(
                    TrackerStep183("rec", ""),
                    TrackerStep183("rec", "  "),
                    TrackerStep183("term", ""),
                    TrackerStep183("clear", ""),
                    TrackerStep183("count", ""),
                    TrackerStep183("isterm", ""),
                ),
            )
        )
        // Eviction: maxTracked=3, five distinct fingerprints recorded.
        // Which two are dropped is unspecified, so only the count is
        // asserted here (production-only, no oracle comparison).
        run {
            val t = DecryptRetryTracker(5, 3)
            val fps = listOf("ev1", "ev2", "ev3", "ev4", "ev5")
            for (fp in fps) t.recordCryptoFailure(fp)
            val zeroCounts = fps.count { t.failureCount(it) == 0 }
            assertEquals(2, zeroCounts, "evicted count")
            assertTrue(fps.all { t.failureCount(it) <= 1 }, "no double count")
        }
        // Eviction via markTerminal overflow: terminal set also counts.
        run {
            val t = DecryptRetryTracker(5, 3)
            t.markTerminal("t1")
            t.markTerminal("t2")
            t.markTerminal("t3")
            t.markTerminal("t4")
            val marked = listOf("t1", "t2", "t3", "t4").count { t.isTerminal(it) }
            assertEquals(3, marked, "terminal kept at cap")
        }
    }

    @Test
    fun randomTrackerMatchesProduction() {
        val rnd = Random(183004)
        repeat(2000) { checkTracker183(randomTrackerCase183(rnd)) }
    }

    @Test
    fun sweepTrackerBoundaries() {
        // All results x ack, then count, from fresh, for each maxAttempts.
        for (max in listOf(1, 2, 3, 5)) {
            for (kind in TRACKER_RESULTS183) {
                checkTracker183(
                    TrackerCase183(
                        max, 256,
                        listOf(
                            TrackerStep183("ack", "e1", null, kind),
                            TrackerStep183("count", "e1"),
                        ),
                    )
                )
            }
        }
        // Failure-count ladder 0..6 with maxAttempts 2.
        for (n in 0..6) {
            val steps = ArrayList<TrackerStep183>()
            repeat(n) { steps.add(TrackerStep183("rec", "fp1")) }
            steps.add(TrackerStep183("count", "fp1"))
            checkTracker183(TrackerCase183(2, 256, steps))
        }
        // record then clear then re-record; term then clear; double clear.
        for (first in listOf("rec", "term")) {
            for (second in listOf("clear", "rec", "term")) {
                checkTracker183(
                    TrackerCase183(
                        5, 256,
                        listOf(
                            TrackerStep183(first, "fp1"),
                            TrackerStep183(second, "fp1"),
                            TrackerStep183("count", "fp1"),
                            TrackerStep183("isterm", "fp1"),
                        ),
                    )
                )
            }
        }
        // Two-sender ownership matrix, then clear one sender.
        for (s1 in listOf("alice", "bob")) {
            for (s2 in listOf("alice", "bob")) {
                checkTracker183(
                    TrackerCase183(
                        5, 256,
                        listOf(
                            TrackerStep183("rec2", "fp1", s1),
                            TrackerStep183("rec2", "fp2", s2),
                            TrackerStep183("clearsender", null, "alice"),
                            TrackerStep183("count", "fp1"),
                            TrackerStep183("count", "fp2"),
                        ),
                    )
                )
            }
        }
    }
}
