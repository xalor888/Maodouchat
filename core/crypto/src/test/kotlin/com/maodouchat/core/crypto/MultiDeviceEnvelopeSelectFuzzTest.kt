// Fuzz / random-oracle tests for MultiDeviceEnvelopePolicy entry
// selection (checklist line 931, 151st block). Test-only, zero
// production changes. The oracle is hand-written from the production
// source: entries addressed to me (or to nobody) come first, an exact
// device match wins, device 1 falls back to a device-1 entry, and a
// lone entry with an omitted device id is compacted onto this device.
// The parse side (parse / parseEntry) is already fuzz-covered by
// block policy-fuzz-nov22; only selectEntry, isRecognizedAlgorithm
// and looksLikeOmittedAlgorithmWire are new here. Blank/trim/random
// helpers are shared from the sibling cluster-A file (same package).
package com.maodouchat.core.crypto

import com.maodouchat.core.model.ParsedEnvelope
import com.maodouchat.core.model.ParsedEntry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONArray
import org.json.JSONObject

// ---------- oracle ----------

internal fun isRecognizedAlgorithmOracle151(algorithm: String): Boolean =
    isBlankOracle151(algorithm) || algorithm == "signal-multi-device-v1"

internal fun trimStartOracle151(s: String): String {
    var start = 0
    while (start < s.length && s[start].code in BLANK_UNITS_ORACLE151) start++
    return s.substring(start)
}

internal fun looksLikeOmittedAlgorithmWireOracle151(content: String): Boolean {
    val t = trimStartOracle151(content)
    if (!t.startsWith("{")) return false
    return "\"senderDeviceId\"" in t && "\"entries\"" in t && "\"ciphertext\"" in t
}

internal fun selectEntryOracle151(
    envelope: ParsedEnvelope,
    currentUserId: String?,
    localDeviceId: Int,
): ParsedEntry? {
    val mine = envelope.entries.filter { it.recipientUserId == null || it.recipientUserId == currentUserId }
    mine.firstOrNull { it.recipientDeviceId == localDeviceId }?.let { return it }
    if (localDeviceId == 1) {
        mine.firstOrNull { it.recipientDeviceId == 1 }?.let { return it }
    }
    val compact = mine.singleOrNull()
    if (compact != null && compact.recipientDeviceIdOmitted) {
        return compact.copy(recipientDeviceId = localDeviceId)
    }
    return null
}

// ---------- generators ----------

private val USER_POOL151 = arrayOf<String?>(
    null, "me", "u1", "u2", "other", "", " ", "me ", " me", "é",
)

private val DEVICE_POOL151 = intArrayOf(1, 2, 3, 255, 0, -1, 256, 1000)

private val ALGORITHM_POOL151 = arrayOf(
    "", " ", "signal-multi-device-v1", "signal-multi-device-v2",
    "signal", "x", "SIGNAL-MULTI-DEVICE-V1", " signal-multi-device-v1",
)

private val CIPHER_POOL151 = arrayOf("", "c", "ciphertext-1", "é", "a b", "x".repeat(40))

private val TYPE_POOL151 = arrayOf<String?>(null, "", "text", "prekey", "x")

internal fun randomEntry151(rnd: Random): ParsedEntry =
    ParsedEntry(
        recipientUserId = if (rnd.nextInt(10) < 6) USER_POOL151[rnd.nextInt(USER_POOL151.size)] else randomText151(rnd, 6),
        recipientDeviceId = if (rnd.nextInt(10) < 7) DEVICE_POOL151[rnd.nextInt(DEVICE_POOL151.size)] else rnd.nextInt(-5, 301),
        recipientDeviceIdOmitted = rnd.nextBoolean(),
        ciphertextType = TYPE_POOL151[rnd.nextInt(TYPE_POOL151.size)],
        ciphertext = if (rnd.nextInt(10) < 8) CIPHER_POOL151[rnd.nextInt(CIPHER_POOL151.size)] else randomText151(rnd, 8),
    )

internal fun randomEnvelope151(rnd: Random): ParsedEnvelope =
    ParsedEnvelope(
        version = if (rnd.nextInt(10) < 7) 3 else rnd.nextInt(0, 6),
        algorithm = if (rnd.nextInt(10) < 7) ALGORITHM_POOL151[rnd.nextInt(ALGORITHM_POOL151.size)] else randomText151(rnd, 10),
        senderDeviceId = if (rnd.nextInt(10) < 7) DEVICE_POOL151[rnd.nextInt(DEVICE_POOL151.size)] else rnd.nextInt(-5, 301),
        payloadType = TYPE_POOL151[rnd.nextInt(TYPE_POOL151.size)],
        entries = List(rnd.nextInt(0, 5)) { randomEntry151(rnd) },
    )

internal fun randomWireContent151(rnd: Random): String {
    val markers = listOf("\"senderDeviceId\"", "\"entries\"", "\"ciphertext\"")
    val sb = StringBuilder()
    if (rnd.nextBoolean()) sb.append(" ".repeat(rnd.nextInt(0, 3)))
    if (rnd.nextInt(10) < 8) sb.append("{") else sb.append(rnd.nextInt(10).toString())
    for (m in markers) {
        if (rnd.nextInt(10) < 8) sb.append(m)
        if (rnd.nextBoolean()) sb.append(" ")
    }
    if (rnd.nextBoolean()) sb.append("}")
    if (rnd.nextBoolean()) sb.append(randomText151(rnd, 6))
    return sb.toString()
}

internal fun entryJson151(e: ParsedEntry): JSONObject {
    val o = JSONObject()
    e.recipientUserId?.let { o.put("ru", it) }
    o.put("rd", e.recipientDeviceId)
    o.put("om", e.recipientDeviceIdOmitted)
    e.ciphertextType?.let { o.put("ct", it) }
    o.put("c", e.ciphertext)
    return o
}

internal fun envelopeJson151(env: ParsedEnvelope): JSONObject {
    val o = JSONObject()
    o.put("v", env.version)
    o.put("a", env.algorithm)
    o.put("sd", env.senderDeviceId)
    env.payloadType?.let { o.put("pt", it) }
    val arr = JSONArray()
    env.entries.forEach { arr.put(entryJson151(it)) }
    o.put("e", arr)
    return o
}

internal fun wireEnvSelect151(env: ParsedEnvelope, me: String?, dev: Int): String {
    val root = JSONObject()
    root.put("env", envelopeJson151(env))
    me?.let { root.put("me", it) }
    root.put("dev", dev)
    return root.toString()
}

class MultiDeviceEnvelopeSelectFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val e1 = ParsedEntry("me", 2, false, null, "c1")
        val e2 = ParsedEntry("me", 1, false, null, "c2")
        val e3 = ParsedEntry("other", 2, false, null, "c3")
        val e4 = ParsedEntry(null, 3, false, null, "c4")
        val env = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(e1, e2, e3, e4))
        // Exact device match wins, even when it is not first.
        assertEquals(e1, selectEntryOracle151(env, "me", 2))
        assertEquals(selectEntryOracle151(env, "me", 2), MultiDeviceEnvelopePolicy.selectEntry(env, "me", 2))
        // Entries for other users never match.
        assertEquals(e2, selectEntryOracle151(env, "me", 1))
        assertEquals(selectEntryOracle151(env, "me", 1), MultiDeviceEnvelopePolicy.selectEntry(env, "me", 1))
        // Null recipient matches anyone.
        assertEquals(e4, selectEntryOracle151(env, "nobody", 3))
        assertEquals(selectEntryOracle151(env, "nobody", 3), MultiDeviceEnvelopePolicy.selectEntry(env, "nobody", 3))
        // No device match and more than one of mine: null, not compacted.
        assertNull(selectEntryOracle151(env, "me", 9))
        assertEquals(selectEntryOracle151(env, "me", 9), MultiDeviceEnvelopePolicy.selectEntry(env, "me", 9))
        // Lone entry with an omitted device id compacts onto this device.
        val compact = ParsedEntry("me", 99, true, null, "cc")
        val env2 = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(compact))
        assertEquals(compact.copy(recipientDeviceId = 7), selectEntryOracle151(env2, "me", 7))
        assertEquals(selectEntryOracle151(env2, "me", 7), MultiDeviceEnvelopePolicy.selectEntry(env2, "me", 7))
        // Lone entry with a present device id does not compact.
        val single = ParsedEntry("me", 5, false, null, "cc")
        val env3 = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(single))
        assertNull(selectEntryOracle151(env3, "me", 7))
        assertEquals(selectEntryOracle151(env3, "me", 7), MultiDeviceEnvelopePolicy.selectEntry(env3, "me", 7))
        // Device-1 fallback: an exact match is preferred, otherwise the
        // device-1 entry is returned when local is device 1.
        val env4 = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(e2, e1))
        assertEquals(e2, selectEntryOracle151(env4, "me", 1))
        // Empty envelope: nothing to select.
        val empty = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, emptyList())
        assertNull(selectEntryOracle151(empty, "me", 1))
        assertEquals(selectEntryOracle151(empty, "me", 1), MultiDeviceEnvelopePolicy.selectEntry(empty, "me", 1))
        // Algorithm recognition: blank counts as recognized, anything else
        // must be the exact string.
        for (a in listOf("", " ", "  \t ")) {
            assertEquals(true, isRecognizedAlgorithmOracle151(a))
            assertEquals(isRecognizedAlgorithmOracle151(a), MultiDeviceEnvelopePolicy.isRecognizedAlgorithm(a))
        }
        assertEquals(true, isRecognizedAlgorithmOracle151("signal-multi-device-v1"))
        assertEquals(false, isRecognizedAlgorithmOracle151("signal-multi-device-v2"))
        assertEquals(false, isRecognizedAlgorithmOracle151(" signal-multi-device-v1"))
        // Omitted-algorithm wire: all three markers behind a "{".
        val wire = """{"senderDeviceId":1,"entries":[],"ciphertext":"x"}"""
        assertEquals(true, looksLikeOmittedAlgorithmWireOracle151(wire))
        assertEquals(looksLikeOmittedAlgorithmWireOracle151(wire), MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire(wire))
        assertEquals(true, looksLikeOmittedAlgorithmWireOracle151("  \n$wire"))
        assertEquals(false, looksLikeOmittedAlgorithmWireOracle151("""{"senderDeviceId":1,"entries":[]}"""))
        assertEquals(false, looksLikeOmittedAlgorithmWireOracle151("""["senderDeviceId","entries","ciphertext"]"""))
        assertEquals(false, looksLikeOmittedAlgorithmWireOracle151(""))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(151003)
        repeat(1500) {
            val env = randomEnvelope151(rnd)
            val me = if (rnd.nextInt(10) < 7) USER_POOL151[rnd.nextInt(USER_POOL151.size)] else randomText151(rnd, 6)
            val dev = if (rnd.nextInt(10) < 7) DEVICE_POOL151[rnd.nextInt(DEVICE_POOL151.size)] else rnd.nextInt(-5, 301)
            assertEquals(
                selectEntryOracle151(env, me, dev),
                MultiDeviceEnvelopePolicy.selectEntry(env, me, dev),
            )
            val algorithm = if (rnd.nextInt(10) < 7) ALGORITHM_POOL151[rnd.nextInt(ALGORITHM_POOL151.size)] else randomText151(rnd, 10)
            assertEquals(
                isRecognizedAlgorithmOracle151(algorithm),
                MultiDeviceEnvelopePolicy.isRecognizedAlgorithm(algorithm),
            )
            val content = randomWireContent151(rnd)
            assertEquals(
                looksLikeOmittedAlgorithmWireOracle151(content),
                MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire(content),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // User x device grid for selection, one entry at a time.
        val users = listOf<String?>(null, "me", "other", "", " ")
        val devs = listOf(1, 2, 3, 255, 0, -1)
        for (u in users) {
            for (d in devs) {
                for (omitted in listOf(false, true)) {
                    val entry = ParsedEntry(u, d, omitted, null, "c")
                    val env = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(entry))
                    for (me in users) {
                        for (local in devs) {
                            assertEquals(
                                selectEntryOracle151(env, me, local),
                                MultiDeviceEnvelopePolicy.selectEntry(env, me, local),
                            )
                        }
                    }
                }
            }
        }
        // Two-entry order sweep: first exact match wins.
        val first = ParsedEntry("me", 2, false, null, "first")
        val second = ParsedEntry("me", 2, false, null, "second")
        val both = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(first, second))
        assertEquals(first, selectEntryOracle151(both, "me", 2))
        assertEquals(selectEntryOracle151(both, "me", 2), MultiDeviceEnvelopePolicy.selectEntry(both, "me", 2))
        val swapped = ParsedEnvelope(3, "signal-multi-device-v1", 1, null, listOf(second, first))
        assertEquals(second, selectEntryOracle151(swapped, "me", 2))
        assertEquals(selectEntryOracle151(swapped, "me", 2), MultiDeviceEnvelopePolicy.selectEntry(swapped, "me", 2))
        // Algorithm grid.
        for (a in ALGORITHM_POOL151) {
            assertEquals(
                isRecognizedAlgorithmOracle151(a),
                MultiDeviceEnvelopePolicy.isRecognizedAlgorithm(a),
            )
        }
        // Wire-marker grid: every subset of the three markers.
        val markers = listOf("\"senderDeviceId\"", "\"entries\"", "\"ciphertext\"")
        for (mask in 0 until 8) {
            val parts = markers.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val content = "{" + parts.joinToString(",") + "}"
            assertEquals(
                looksLikeOmittedAlgorithmWireOracle151(content),
                MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire(content),
            )
            // Same markers without the leading brace never match.
            val bare = parts.joinToString(",")
            assertEquals(
                looksLikeOmittedAlgorithmWireOracle151(bare),
                MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire(bare),
            )
        }
        // Leading-whitespace sweep for the brace check.
        for (ws in listOf("", " ", "\t", "\n", "\u00A0", "\u2028", "\u2029")) {
            val content = ws + """{"senderDeviceId":1,"entries":[],"ciphertext":"x"}"""
            assertEquals(
                looksLikeOmittedAlgorithmWireOracle151(content),
                MultiDeviceEnvelopePolicy.looksLikeOmittedAlgorithmWire(content),
            )
        }
    }
}
