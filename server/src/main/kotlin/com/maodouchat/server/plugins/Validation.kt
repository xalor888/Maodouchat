package com.maodouchat.server.plugins

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
    (this[key] as? JsonPrimitive)?.content

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
    val maxLength = when (type) {
        "IMAGE", "GIF", "VOICE", "VIDEO", "FILE" -> MAX_MEDIA_CONTENT_LENGTH
        "STICKER" -> MAX_STICKER_WIRE_CONTENT_LENGTH
        "LOCATION" -> MAX_STICKER_WIRE_CONTENT_LENGTH
        "SK_DIST" -> MAX_HIDDEN_MESSAGE_CONTENT_LENGTH
        else -> MAX_TEXT_CONTENT_LENGTH
    }
    if (type !in ALLOWED_MESSAGE_TYPES) return false
    if (content.isBlank() || content.length > maxLength) return false
    if (id != null && !CLIENT_MESSAGE_ID_REGEX.matches(id)) return false
    if (requireGroupSenderKey && !isValidGroupHumanPayload(content, type)) return false
    return true
}

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
    chatRepo: com.maodouchat.server.repository.ChatRepository
): Boolean {
    if (groupId.isBlank()) return groupMemberIds.isEmpty() && !groupInvite
    if (callId.isBlank()) return false
    if (!CALL_ID_REGEX.matches(groupId) || groupMemberIds.size !in 2..MAX_MESH_CALL_MEMBERS) return false
    val distinctMembers = groupMemberIds.distinct()
    if (distinctMembers.size != groupMemberIds.size || fromUserId !in distinctMembers || toUserId !in distinctMembers) return false
    val chat = chatRepo.getChatById(groupId) ?: return false
    if (!chat.isGroup) return false
    val actualMembers = chat.participants.map { it.id }.toSet()
    return distinctMembers.all { it in actualMembers }
}

