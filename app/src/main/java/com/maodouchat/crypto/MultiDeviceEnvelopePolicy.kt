package com.maodouchat.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Live 1:1 multi-device envelopes were encoded with encodeDefaults=false, so
 * version/algorithm (and sometimes recipientDeviceId) are omitted:
 * `{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"..."}]}`
 *
 * kotlinx defaults fill omitted version/algorithm, but a missing required
 * recipientDeviceId used to fail the whole decode — decrypt never ran and the
 * bubble showed raw JSON. Parse leniently so crypto can still run.
 */
object MultiDeviceEnvelopePolicy {
    const val MULTI_DEVICE_ENVELOPE_VERSION = 3
    const val ALGORITHM_SIGNAL_MULTI_DEVICE = "signal-multi-device-v1"
    const val DEFAULT_DEVICE_ID = 1

    data class ParsedEnvelope(
        val version: Int,
        val algorithm: String,
        val senderDeviceId: Int,
        val payloadType: String?,
        val entries: List<ParsedEntry>
    )

    data class ParsedEntry(
        val recipientUserId: String?,
        val recipientDeviceId: Int,
        val recipientDeviceIdOmitted: Boolean,
        val ciphertextType: String?,
        val ciphertext: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun isRecognizedAlgorithm(algorithm: String): Boolean =
        algorithm.isBlank() || algorithm == ALGORITHM_SIGNAL_MULTI_DEVICE

    fun looksLikeOmittedAlgorithmWire(content: String): Boolean {
        val t = content.trimStart()
        if (!t.startsWith("{")) return false
        return t.contains("\"senderDeviceId\"") &&
            t.contains("\"entries\"") &&
            t.contains("\"ciphertext\"")
    }

    fun parse(content: String): ParsedEnvelope? {
        val root = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        val entriesEl = root["entries"] as? JsonArray ?: return null
        if (entriesEl.isEmpty()) return null
        val algorithm = root.string("algorithm").orEmpty()
        if (!isRecognizedAlgorithm(algorithm)) return null
        val entries = entriesEl.mapNotNull { parseEntry(it) }
        if (entries.isEmpty()) return null
        val version = root.int("version") ?: 0
        if (version != 0 && version != MULTI_DEVICE_ENVELOPE_VERSION) return null
        return ParsedEnvelope(
            version = if (version == 0) MULTI_DEVICE_ENVELOPE_VERSION else version,
            algorithm = algorithm.ifBlank { ALGORITHM_SIGNAL_MULTI_DEVICE },
            senderDeviceId = root.int("senderDeviceId") ?: DEFAULT_DEVICE_ID,
            payloadType = root.string("payloadType"),
            entries = entries
        )
    }

    fun selectEntry(
        envelope: ParsedEnvelope,
        currentUserId: String?,
        localDeviceId: Int
    ): ParsedEntry? {
        val mine = envelope.entries.filter { entry ->
            entry.recipientUserId == null || entry.recipientUserId == currentUserId
        }
        mine.firstOrNull { it.recipientDeviceId == localDeviceId }?.let { return it }
        if (localDeviceId == DEFAULT_DEVICE_ID) {
            mine.firstOrNull { it.recipientDeviceId == DEFAULT_DEVICE_ID }?.let { return it }
        }
        // Compact historical envelope: a single entry with omitted device id is for
        // this device (encodeDefaults=false never wrote recipientDeviceId).
        val compact = mine.singleOrNull()
        if (compact != null && compact.recipientDeviceIdOmitted) {
            return compact.copy(recipientDeviceId = localDeviceId)
        }
        return null
    }

    private fun parseEntry(element: JsonElement): ParsedEntry? {
        val obj = element as? JsonObject ?: return null
        val ciphertext = obj.string("ciphertext")?.takeIf { it.isNotBlank() } ?: return null
        val omitted = !obj.containsKey("recipientDeviceId")
        val deviceId = obj.int("recipientDeviceId") ?: DEFAULT_DEVICE_ID
        if (deviceId !in 1..255) return null
        return ParsedEntry(
            recipientUserId = obj.string("recipientUserId")?.takeIf { it.isNotBlank() },
            recipientDeviceId = deviceId,
            recipientDeviceIdOmitted = omitted,
            ciphertextType = obj.string("ciphertextType"),
            ciphertext = ciphertext
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
