package com.maodouchat.server.repository

import com.maodouchat.server.model.MessageResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * E2EE retries may produce different ciphertext for the same client message id. The first
 * committed row remains authoritative; a matching retry is acknowledged without rewriting.
 * Ciphertext and ciphertextType are excluded because PREKEY -> SIGNAL is a normal retry, while
 * sender/device/group routing metadata remains immutable. Plaintext traffic is byte-exact.
 */
internal object MessageIdempotencyPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun matches(
        existing: MessageResponse?,
        senderId: String,
        chatId: String,
        content: String,
        type: String,
        sealedSender: Boolean,
    ): Boolean = existing != null &&
        existing.senderId == senderId &&
        existing.chatId == chatId &&
        existing.type == type &&
        existing.sealedSender == sealedSender &&
        equivalentContent(existing.content, content, type)

    private fun equivalentContent(existing: String, retry: String, outerType: String): Boolean {
        // An exact replay cannot change the committed logical message. Accept it even when it
        // uses a legacy encrypted-envelope shape that the current parser no longer recognizes.
        // This is required for crash/reconnect retries of SK_DIST control messages.
        if (existing == retry) return true

        val left = stableEncryptedMetadata(existing, outerType)
        val right = stableEncryptedMetadata(retry, outerType)
        if (left != null || right != null) return left != null && left == right

        // A known Signal shape that failed strict parsing must not use byte equality to bypass
        // envelope validation. Arbitrary user JSON remains ordinary byte-exact plaintext.
        if (looksLikeKnownEncryptedEnvelope(existing) || looksLikeKnownEncryptedEnvelope(retry)) {
            return false
        }
        return existing == retry
    }

    private fun stableEncryptedMetadata(content: String, outerType: String): String? {
        if (content.length > MAX_MESSAGE_WIRE_LENGTH) return null
        val root = parseObject(content) ?: return null
        val algorithm = when {
            "algorithm" in root -> root.stringValue("algorithm")
            root["entries"] is JsonArray && root.stringValue("payloadType") != null -> MULTI_ALGORITHM
            "ciphertext" in root && root.stringValue("payloadType") != null -> SINGLE_ALGORITHM
            else -> null
        }
        return when (algorithm) {
            MULTI_ALGORITHM -> stableMultiMetadata(root, outerType)
            SINGLE_ALGORITHM -> stableSingleMetadata(root, outerType)
            SENDER_KEY_ALGORITHM -> stableSenderKeyMetadata(root, outerType)
            SENDER_KEY_DISTRIBUTION_ALGORITHM -> stableDistributionMetadata(root, outerType)
            else -> null
        }
    }

    private fun stableMultiMetadata(root: JsonObject, outerType: String): String? {
        if (root.stringValueOrDefault("algorithm", MULTI_ALGORITHM) != MULTI_ALGORITHM) return null
        if (root.intValueOrDefault("version", MULTI_VERSION) != MULTI_VERSION) return null
        val senderDeviceId = root.intValueOrDefault("senderDeviceId", DEFAULT_DEVICE_ID)
            ?.takeIf(::isValidDeviceId) ?: return null
        val payloadType = root.stringValue("payloadType")?.takeIf { it == outerType } ?: return null
        val targets = stableTargets(root) ?: return null
        return metadata(
            "multi",
            MULTI_VERSION.toString(),
            senderDeviceId.toString(),
            payloadType,
            targets,
        )
    }

    private fun stableSingleMetadata(root: JsonObject, outerType: String): String? {
        if (root.stringValueOrDefault("algorithm", SINGLE_ALGORITHM) != SINGLE_ALGORITHM) return null
        if (root.intValueOrDefault("version", SINGLE_VERSION) != SINGLE_VERSION) return null
        val senderDeviceId = root.intValueOrDefault("senderDeviceId", DEFAULT_DEVICE_ID)
            ?.takeIf(::isValidDeviceId) ?: return null
        val recipientDeviceId = root.intValueOrDefault("recipientDeviceId", DEFAULT_DEVICE_ID)
            ?.takeIf(::isValidDeviceId) ?: return null
        val payloadType = root.stringValue("payloadType")?.takeIf { it == outerType } ?: return null
        if (!root.hasValidCiphertext() || !root.hasValidOptionalCiphertextType()) return null
        return metadata(
            "single",
            SINGLE_VERSION.toString(),
            senderDeviceId.toString(),
            recipientDeviceId.toString(),
            payloadType,
        )
    }

    private fun stableSenderKeyMetadata(root: JsonObject, outerType: String): String? {
        if (root.stringValue("algorithm") != SENDER_KEY_ALGORITHM) return null
        if (root.intValue("version") != 1) return null
        val groupId = root.nonBlankString("groupId") ?: return null
        val epoch = root.longValue("epoch")?.takeIf { it > 0L } ?: return null
        val senderDeviceId = root.intValue("senderDeviceId")?.takeIf(::isValidDeviceId) ?: return null
        val distributionId = root.nonBlankString("distributionId") ?: return null
        val payloadType = root.stringValue("payloadType")?.takeIf { it == outerType } ?: return null
        if (!root.hasValidCiphertext()) return null
        return metadata(
            "sender-key",
            groupId,
            epoch.toString(),
            senderDeviceId.toString(),
            distributionId,
            payloadType,
        )
    }

    private fun stableDistributionMetadata(root: JsonObject, outerType: String): String? {
        if (outerType != "SK_DIST") return null
        if (root.stringValue("algorithm") != SENDER_KEY_DISTRIBUTION_ALGORITHM) return null
        if (root.intValue("version") != 1) return null
        val groupId = root.nonBlankString("groupId") ?: return null
        val epoch = root.longValue("epoch")?.takeIf { it > 0L } ?: return null
        val senderDeviceId = root.intValue("senderDeviceId")?.takeIf(::isValidDeviceId) ?: return null
        val distributionId = root.nonBlankString("distributionId") ?: return null
        val distributionMessage = root.stringValue("distributionMessage")
            ?.takeIf { it.isValidBase64() } ?: return null
        return metadata(
            "sender-key-distribution",
            groupId,
            epoch.toString(),
            senderDeviceId.toString(),
            distributionId,
            distributionMessage,
        )
    }

    private fun stableTargets(root: JsonObject): String? {
        val entries = root["entries"] as? JsonArray ?: return null
        if (entries.isEmpty()) return null
        val allowDefaultDevice = entries.size == 1
        val targets = mutableListOf<String>()
        entries.forEach { element ->
            val entry = element as? JsonObject ?: return null
            val userId = if ("recipientUserId" in entry) {
                entry.nonBlankString("recipientUserId") ?: return null
            } else {
                null
            }
            val deviceId = if ("recipientDeviceId" in entry) {
                entry.intValue("recipientDeviceId")?.takeIf(::isValidDeviceId) ?: return null
            } else {
                if (!allowDefaultDevice) return null
                DEFAULT_DEVICE_ID
            }
            if (!entry.hasValidCiphertext() || !entry.hasValidOptionalCiphertextType()) return null
            val userTarget = userId?.let { "${it.length}:$it" } ?: "-"
            targets += "$userTarget#$deviceId"
        }
        // Keep duplicates: changing entry multiplicity is a logical routing change.
        return targets.sorted().joinToString(";")
    }

    private fun looksLikeKnownEncryptedEnvelope(content: String): Boolean {
        val root = parseObject(content) ?: return false
        val algorithm = root.stringValue("algorithm")
        if (algorithm in KNOWN_ALGORITHMS) return true
        val payloadType = root.stringValue("payloadType") ?: return false
        return payloadType.isNotBlank() && (root["entries"] is JsonArray || "ciphertext" in root)
    }

    private fun parseObject(content: String): JsonObject? {
        val trimmed = content.trim()
        if (!trimmed.startsWith('{')) return null
        return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.stringValue(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.nonBlankString(name: String): String? =
        stringValue(name)?.takeIf(String::isNotBlank)

    private fun JsonObject.intValue(name: String): Int? {
        val value = this[name] as? JsonPrimitive ?: return null
        return value.takeUnless { it.isString }?.content?.toIntOrNull()
    }

    private fun JsonObject.longValue(name: String): Long? {
        val value = this[name] as? JsonPrimitive ?: return null
        return value.takeUnless { it.isString }?.content?.toLongOrNull()
    }

    private fun JsonObject.stringValueOrDefault(name: String, default: String): String? =
        if (name in this) stringValue(name) else default

    private fun JsonObject.intValueOrDefault(name: String, default: Int): Int? =
        if (name in this) intValue(name) else default

    private fun JsonObject.hasValidCiphertext(): Boolean =
        stringValue("ciphertext")?.isValidBase64() == true

    private fun JsonObject.hasValidOptionalCiphertextType(): Boolean =
        "ciphertextType" !in this || stringValue("ciphertextType") in VALID_CIPHERTEXT_TYPES

    private fun String.isValidBase64(): Boolean {
        if (isEmpty() || length % 4 != 0) return false
        val paddingStart = indexOf('=').let { if (it < 0) length else it }
        if (length - paddingStart > 2) return false
        return indices.all { index ->
            val char = this[index]
            if (index < paddingStart) {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '+' || char == '/'
            } else {
                char == '='
            }
        }
    }

    private fun isValidDeviceId(value: Int): Boolean = value in 1..255

    private fun metadata(vararg fields: String): String = fields.joinToString("\u0000")

    private const val MULTI_ALGORITHM = "signal-multi-device-v1"
    private const val SINGLE_ALGORITHM = "signal-v2"
    private const val SENDER_KEY_ALGORITHM = "signal-sender-key-v1"
    private const val SENDER_KEY_DISTRIBUTION_ALGORITHM = "signal-sender-key-distribution-v1"
    private const val MULTI_VERSION = 3
    private const val SINGLE_VERSION = 2
    private const val DEFAULT_DEVICE_ID = 1
    private const val MAX_MESSAGE_WIRE_LENGTH = 2_750_000
    private val KNOWN_ALGORITHMS = setOf(
        MULTI_ALGORITHM,
        SINGLE_ALGORITHM,
        SENDER_KEY_ALGORITHM,
        SENDER_KEY_DISTRIBUTION_ALGORITHM,
    )
    private val VALID_CIPHERTEXT_TYPES = setOf("prekey", "signal", "unknown")
}
