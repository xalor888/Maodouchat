package com.maodouchat.server.plugins

import com.maodouchat.server.model.MessageResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 共享的消息/信令验证常量与函数。
 *
 * Sockets.kt 和 Routing.kt 均使用这些定义，避免重复维护。
 */
internal const val MAX_TEXT_CONTENT_LENGTH = 16_384
// E2EE wire content is larger: UTF-8 plaintext is Base64 encoded once per recipient device,
// plus per-entry Signal/JSON overhead. The larger limit is granted only after the envelope's
// algorithm and required structure have been validated below.
internal const val MAX_TEXT_WIRE_CONTENT_LENGTH = 512_000
// 1.2 MB media becomes ~1.6 MB Base64, then grows again inside a Signal ciphertext envelope.
internal const val MAX_MEDIA_CONTENT_LENGTH = 2_750_000
internal const val MAX_HIDDEN_MESSAGE_CONTENT_LENGTH = 512_000
internal const val MAX_STICKER_WIRE_CONTENT_LENGTH = 65_536
internal const val MAX_SIGNALING_PAYLOAD_LENGTH = 32_768
// BCrypt 实现静默截断 72 字节之后的输入（前 72 字节相同的密码互为等价）——上限必须 ≤72 字节
internal const val MAX_PASSWORD_BYTES = 72

/** 密码合法性：≥6 字符且 UTF-8 字节数 ≤72（BCrypt 截断边界）。 */
internal fun isValidPassword(password: String): Boolean {
    if (password.length < 6) return false
    return password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES
}
internal const val MAX_CALL_ID_LENGTH = 100
internal const val MAX_MESH_CALL_MEMBERS = 6
internal const val MAX_POST_CONTENT_LENGTH = 2_000
internal const val MAX_POST_IMAGES = 9
internal const val MAX_POST_IMAGE_URL_LENGTH = 500
internal const val MAX_COMMENT_CONTENT_LENGTH = 800

internal val ALLOWED_POST_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")
internal val ALLOWED_MESSAGE_TYPES = setOf("TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE", "VIDEO", "FILE", "SK_DIST")
// FAILED is client-local only; the server delivery ladder is SENT → DELIVERED → READ
internal val ALLOWED_STATUSES = setOf("SENT", "DELIVERED", "READ")
internal val ALLOWED_SIGNALING_TYPES = setOf("offer", "answer", "ice-candidate", "hang-up", "busy", "reject")
internal val CLIENT_MESSAGE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")
internal val CALL_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")

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

private fun isValidLargeTextWireEnvelope(content: String, type: String): Boolean {
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

internal fun isValidMessagePayload(
    content: String,
    type: String,
    id: String?,
    requireGroupSenderKey: Boolean = false
): Boolean {
    if (type !in ALLOWED_MESSAGE_TYPES) return false
    if (content.isBlank()) return false
    if (id != null && !CLIENT_MESSAGE_ID_REGEX.matches(id)) return false

    val maxLength = when (type) {
        "IMAGE", "GIF", "VOICE", "VIDEO", "FILE" -> MAX_MEDIA_CONTENT_LENGTH
        "STICKER" -> MAX_STICKER_WIRE_CONTENT_LENGTH
        "LOCATION" -> MAX_STICKER_WIRE_CONTENT_LENGTH
        "SK_DIST" -> MAX_HIDDEN_MESSAGE_CONTENT_LENGTH
        "TEXT", "MARKDOWN" -> {
            if (content.length > MAX_TEXT_WIRE_CONTENT_LENGTH) return false
            if (content.length > MAX_TEXT_CONTENT_LENGTH && !isValidLargeTextWireEnvelope(content, type)) {
                return false
            }
            MAX_TEXT_WIRE_CONTENT_LENGTH
        }
        else -> MAX_TEXT_CONTENT_LENGTH
    }
    if (content.length > maxLength) return false
    if (requireGroupSenderKey && !isValidGroupHumanPayload(content, type)) return false
    return true
}

/**
 * A client retry may skip the send limiter only when it is byte-for-byte the same
 * logical message owned by the authenticated sender.  Looking up an arbitrary id
 * before chat authorization used to let a caller probe global ids and bypass the
 * limiter merely by naming an existing row.
 */
internal fun isMatchingIdempotentMessageRetry(
    existing: MessageResponse?,
    senderId: String,
    chatId: String,
    content: String,
    type: String,
    sealedSender: Boolean,
): Boolean = com.maodouchat.server.repository.MessageIdempotencyPolicy.matches(
    existing = existing,
    senderId = senderId,
    chatId = chatId,
    content = content,
    type = type,
    sealedSender = sealedSender,
)

internal fun isValidSignalPayload(type: String, payload: String): Boolean {
    val payloadRequired = type !in setOf("hang-up", "busy", "reject")
    return type in ALLOWED_SIGNALING_TYPES &&
        (!payloadRequired || payload.isNotBlank()) &&
        payload.length <= MAX_SIGNALING_PAYLOAD_LENGTH
}

internal fun isValidPostPayload(content: String, imageUrls: List<String>, visibility: String): Boolean {
    return (content.isNotBlank() || imageUrls.isNotEmpty()) &&
        content.length <= MAX_POST_CONTENT_LENGTH &&
        imageUrls.size <= MAX_POST_IMAGES &&
        imageUrls.distinct().size == imageUrls.size &&
        imageUrls.all { it.isNotBlank() && it.length <= MAX_POST_IMAGE_URL_LENGTH } &&
        isValidPostVisibility(visibility)
}

internal fun isValidPostVisibility(visibility: String): Boolean = visibility in ALLOWED_POST_VISIBILITIES

internal fun isValidCommentPayload(content: String): Boolean {
    return content.isNotBlank() && content.length <= MAX_COMMENT_CONTENT_LENGTH
}

/** Non-blank session id required so hangup/clear never wipe unrelated 1:1 signaling. */
internal fun isValidCallId(callId: String): Boolean =
    callId.isNotBlank() && callId.length <= MAX_CALL_ID_LENGTH && CALL_ID_REGEX.matches(callId)

internal fun isValidGroupSignalMetadata(
    groupId: String,
    groupMemberIds: List<String>,
    groupInvite: Boolean,
    callId: String,
    fromUserId: String,
    toUserId: String,
    conversationQueryRepository: com.maodouchat.server.repository.ConversationQueryRepository
): Boolean {
    if (groupId.isBlank()) return groupMemberIds.isEmpty() && !groupInvite
    if (callId.isBlank()) return false
    if (!CALL_ID_REGEX.matches(groupId) || groupMemberIds.size !in 2..MAX_MESH_CALL_MEMBERS) return false
    val distinctMembers = groupMemberIds.distinct()
    if (distinctMembers.size != groupMemberIds.size || fromUserId !in distinctMembers || toUserId !in distinctMembers) return false
    val chat = conversationQueryRepository.getById(groupId) ?: return false
    if (!chat.isGroup) return false
    val actualMembers = chat.participants.map { it.id }.toSet()
    return distinctMembers.all { it in actualMembers }
}
