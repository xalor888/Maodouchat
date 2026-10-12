package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Signal 信封校验族（从 Validation.kt 按域拆出，零行为改动）。
 */

internal const val SENDER_KEY_ALGORITHM = "signal-sender-key-v1"
internal const val SENDER_KEY_DISTRIBUTION_ALGORITHM = "signal-sender-key-distribution-v1"
internal const val SIGNAL_MULTI_DEVICE_ALGORITHM = "signal-multi-device-v1"
internal const val SIGNAL_SINGLE_DEVICE_ALGORITHM = "signal-v2"

private const val SIGNAL_MULTI_DEVICE_VERSION = 3
private const val SIGNAL_SINGLE_DEVICE_VERSION = 2
private const val DEFAULT_SIGNAL_DEVICE_ID = 1

private val SENDER_KEY_ALGORITHM_FIELD = Regex(
    "\"algorithm\"\\s*:\\s*\"${Regex.escape(SENDER_KEY_ALGORITHM)}\""
)
private val SENDER_KEY_DISTRIBUTION_ALGORITHM_FIELD = Regex(
    "\"algorithm\"\\s*:\\s*\"${Regex.escape(SENDER_KEY_DISTRIBUTION_ALGORITHM)}\""
)
private val MULTI_DEVICE_ALGORITHM_FIELD = Regex(
    "\"algorithm\"\\s*:\\s*\"${Regex.escape(SIGNAL_MULTI_DEVICE_ALGORITHM)}\""
)
private val SK_DIST_PAYLOAD_TYPE_FIELD = Regex("\"payloadType\"\\s*:\\s*\"SK_DIST\"")

private fun isObjectLike(content: String): Boolean =
    content.firstOrNull { !it.isWhitespace() } == '{'

private fun parseMessageEnvelope(content: String): JsonObject? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{")) return null
    return runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intValue(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.takeUnless { it.isString }?.content?.toIntOrNull()
}

/**
 * Compact historical Signal envelopes were encoded with `encodeDefaults=false`.
 * Defaults are applied only when a field is genuinely absent; an explicitly
 * malformed value (for example a quoted number or JSON null) remains invalid.
 */
private fun JsonObject.intValueOrDefault(key: String, default: Int): Int? =
    if (key in this) intValue(key) else default

private fun JsonObject.stringValueOrDefault(key: String, default: String): String? =
    if (key in this) stringValue(key) else default

private fun JsonObject.longValue(key: String): Long? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.takeUnless { it.isString }?.content?.toLongOrNull()
}

private fun JsonObject.hasNonBlankString(key: String): Boolean =
    !stringValue(key).isNullOrBlank()

private fun String.isValidBase64Ciphertext(): Boolean {
    if (isEmpty() || length % 4 != 0) return false
    val paddingStart = indexOf('=').let { if (it < 0) length else it }
    if (length - paddingStart > 2) return false
    for (index in indices) {
        val char = this[index]
        val valid = if (index < paddingStart) {
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '+' || char == '/'
        } else {
            char == '='
        }
        if (!valid) return false
    }
    return true
}

private fun JsonObject.hasValidCiphertext(): Boolean =
    stringValue("ciphertext")?.isValidBase64Ciphertext() == true

private fun Int?.isValidSignalDeviceId(): Boolean =
    this != null && this in 1..255

private fun JsonObject.hasValidDeviceId(key: String): Boolean =
    intValue(key).isValidSignalDeviceId()

private fun isValidMultiDeviceTextEnvelope(envelope: JsonObject, type: String): Boolean {
    if (envelope.stringValueOrDefault("algorithm", SIGNAL_MULTI_DEVICE_ALGORITHM) != SIGNAL_MULTI_DEVICE_ALGORITHM ||
        envelope.intValueOrDefault("version", SIGNAL_MULTI_DEVICE_VERSION) != SIGNAL_MULTI_DEVICE_VERSION ||
        !envelope.intValueOrDefault("senderDeviceId", DEFAULT_SIGNAL_DEVICE_ID).isValidSignalDeviceId() ||
        envelope.stringValue("payloadType") != type
    ) {
        return false
    }
    val entries = envelope["entries"] as? JsonArray ?: return false
    if (entries.isEmpty()) return false
    // A compact one-entry envelope may omit recipientDeviceId. Once there are
    // multiple entries the field is required to avoid ambiguous fan-out.
    val allowOmittedRecipientDeviceId = entries.size == 1
    return entries.all { element ->
        val entry = element as? JsonObject ?: return@all false
        val recipientUserIdValid = "recipientUserId" !in entry || entry.hasNonBlankString("recipientUserId")
        recipientUserIdValid &&
            (if ("recipientDeviceId" !in entry) {
                allowOmittedRecipientDeviceId
            } else {
                entry.hasValidDeviceId("recipientDeviceId")
            }) &&
            entry.hasValidCiphertext()
    }
}

private fun isValidSingleDeviceTextEnvelope(envelope: JsonObject, type: String): Boolean =
    envelope.stringValueOrDefault("algorithm", SIGNAL_SINGLE_DEVICE_ALGORITHM) == SIGNAL_SINGLE_DEVICE_ALGORITHM &&
        envelope.intValueOrDefault("version", SIGNAL_SINGLE_DEVICE_VERSION) == SIGNAL_SINGLE_DEVICE_VERSION &&
        envelope.intValueOrDefault("senderDeviceId", DEFAULT_SIGNAL_DEVICE_ID).isValidSignalDeviceId() &&
        ("recipientDeviceId" !in envelope || envelope.hasValidDeviceId("recipientDeviceId")) &&
        envelope.stringValue("payloadType") == type &&
        envelope.hasValidCiphertext()

/**
 * Infer the protocol only for compact direct Signal text wires whose algorithm
 * field was omitted. Sender-key envelopes have a different shape and must keep
 * their explicit algorithm/version metadata.
 */
private fun textWireAlgorithm(envelope: JsonObject): String? {
    if ("algorithm" in envelope) return envelope.stringValue("algorithm")
    return when {
        envelope["entries"] is JsonArray -> SIGNAL_MULTI_DEVICE_ALGORITHM
        "ciphertext" in envelope -> SIGNAL_SINGLE_DEVICE_ALGORITHM
        else -> null
    }
}

private fun isValidSenderKeyTextEnvelope(envelope: JsonObject, type: String): Boolean =
    envelope.stringValue("algorithm") == SENDER_KEY_ALGORITHM &&
        envelope.intValue("version") == 1 &&
        envelope.hasNonBlankString("groupId") &&
        (envelope.longValue("epoch") ?: 0L) > 0L &&
        envelope.intValue("senderDeviceId").isValidSignalDeviceId() &&
        envelope.hasNonBlankString("distributionId") &&
        envelope.stringValue("payloadType") == type &&
        envelope.hasValidCiphertext()

/**
 * 原 private（Validation.kt 内唯一调用方是 isValidMessagePayload）：拆出后提为 internal，同模块零行为改动。
 */
internal fun isValidLargeTextWireEnvelope(content: String, type: String): Boolean {
    val envelope = parseMessageEnvelope(content) ?: return false
    return when (textWireAlgorithm(envelope)) {
        SIGNAL_MULTI_DEVICE_ALGORITHM -> isValidMultiDeviceTextEnvelope(envelope, type)
        SIGNAL_SINGLE_DEVICE_ALGORITHM -> isValidSingleDeviceTextEnvelope(envelope, type)
        SENDER_KEY_ALGORITHM -> isValidSenderKeyTextEnvelope(envelope, type)
        else -> false
    }
}

internal fun looksLikeSenderKeyEnvelope(content: String): Boolean {
    return isObjectLike(content) && SENDER_KEY_ALGORITHM_FIELD.containsMatchIn(content)
}

internal fun looksLikeSenderKeyDistributionEnvelope(content: String): Boolean {
    return isObjectLike(content) && SENDER_KEY_DISTRIBUTION_ALGORITHM_FIELD.containsMatchIn(content)
}

/**
 * SK_DIST is sent as a per-device Signal envelope. The plaintext distribution
 * envelope is only present after the recipient decrypts the outer envelope.
 */
internal fun looksLikeEncryptedSenderKeyDistributionEnvelope(content: String): Boolean {
    if (!isObjectLike(content) ||
        !MULTI_DEVICE_ALGORITHM_FIELD.containsMatchIn(content) ||
        !SK_DIST_PAYLOAD_TYPE_FIELD.containsMatchIn(content)
    ) {
        return false
    }
    val envelope = parseMessageEnvelope(content) ?: return false
    val entries = envelope["entries"] as? JsonArray ?: return false
    return envelope.stringValue("algorithm") == SIGNAL_MULTI_DEVICE_ALGORITHM &&
        envelope.stringValue("payloadType") == "SK_DIST" &&
        entries.isNotEmpty()
}

/**
 * Human traffic in GROUP/CHANNEL must be Sender Key (or SK distribution).
 * Bot/system cards stay plaintext on a separate insert path.
 */
internal fun isValidGroupHumanPayload(content: String, type: String): Boolean {
    if (type == "SK_DIST") {
        return looksLikeSenderKeyDistributionEnvelope(content) ||
            looksLikeEncryptedSenderKeyDistributionEnvelope(content)
    }
    return looksLikeSenderKeyEnvelope(content) || looksLikeSenderKeyDistributionEnvelope(content)
}
