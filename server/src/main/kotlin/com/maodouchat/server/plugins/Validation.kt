package com.maodouchat.server.plugins

import com.maodouchat.server.model.MessageResponse

/**
 * 共享的消息/账号验证常量与函数（Signal 信封校验族已拆往 SignalEnvelopeValidation.kt）。
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
// BCrypt 实现静默截断 72 字节之后的输入（前 72 字节相同的密码互为等价）——上限必须 ≤72 字节
internal const val MAX_PASSWORD_BYTES = 72

/**
 * 密码最小长度（G328c：6 → 8）。
 *
 * 为什么改：6 位下限在审计里被点名——配合「无登录失败锁定上限之外的其它约束」，
 * 6 位是离线爆破几秒钟的量级。8 位是 NIST SP 800-63B 的最低建议值，也是不需要
 * 引入长度上限以外的组合规则（那类规则按同一份 NIST 指南反而有害）就能拿到的提升。
 * 注意：这是**新设密码**的下限；既有密码不受影响（校验走哈希比对，不重跑本条）。
 */
internal const val MIN_PASSWORD_LENGTH = 8

/**
 * 密码合法性：≥[MIN_PASSWORD_LENGTH] 字符且 UTF-8 字节数 ≤72（BCrypt 截断边界）。
 * 另拒绝「全同一个字符」——这类密码在留 8 位下限后仍是最常见的弱口令形态。
 */
internal fun isValidPassword(password: String): Boolean {
    if (password.length < MIN_PASSWORD_LENGTH) return false
    if (password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) return false
    if (password.all { it == password[0] }) return false
    return true
}

/**
 * 日志用邮箱脱敏（G328c）：保留首字符与域名，中间打码。
 *
 * 原先登录审计按 INFO 级别打印**完整**邮箱 + 来源 IP——排障确实需要「哪个账号」，
 * 但明文邮箱落进日志文件（并被 docker-compose 的日志轮转保留）是没有必要的 PII 面。
 * 脱敏后仍可区分账号（首字符 + 域名 + 长度），排障够用。
 */
internal fun maskEmail(email: String): String {
    val trimmed = email.trim()
    val at = trimmed.indexOf('@')
    if (at <= 0) return if (trimmed.isEmpty()) "" else "${trimmed.first()}***"
    val local = trimmed.substring(0, at)
    val domain = trimmed.substring(at)
    val head = local.first()
    val tail = if (local.length >= 3) local.last() else null
    return buildString {
        append(head)
        // 至少 3 个星：否则 "a@x.com" 只出一个星，等于把本地部分的长度也说出来了
        append("*".repeat(maxOf(3, local.length - 1)))
        if (tail != null && local.length >= 3) append(tail)
        append(domain)
    }
}
internal const val MAX_POST_CONTENT_LENGTH = 2_000
internal const val MAX_POST_IMAGES = 9
internal const val MAX_POST_IMAGE_URL_LENGTH = 500
internal const val MAX_COMMENT_CONTENT_LENGTH = 800

internal val ALLOWED_POST_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")
internal val ALLOWED_MESSAGE_TYPES = setOf("TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE", "VIDEO", "FILE", "SK_DIST")
// FAILED is client-local only; the server delivery ladder is SENT → DELIVERED → READ
internal val ALLOWED_STATUSES = setOf("SENT", "DELIVERED", "READ")
internal val CLIENT_MESSAGE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")

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
