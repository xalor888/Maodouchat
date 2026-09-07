package com.maodouchat.contacts

import java.nio.charset.StandardCharsets

/**
 * 二维码解析结果密封类 (纯解析与校验，不包含任何导航、网络请求或加密执行逻辑)。
 */
sealed class QrParsedPayload {
    data class User(val userId: String) : QrParsedPayload()
    data class Chat(val chatId: String) : QrParsedPayload()
    data class ChatInvite(val token: String) : QrParsedPayload()
    data class Safety(
        val version: Int,
        val ownerUserId: String,
        val ownerDeviceId: Int,
        val peerUserId: String,
        val peerDeviceId: Int,
        val safetyCode: String? = null,
        val ownerIdentityFingerprint: String? = null,
        val peerIdentityFingerprint: String? = null
    ) : QrParsedPayload()

    data class Invalid(val reason: String) : QrParsedPayload()
}

/**
 * 纯逻辑二维码解析与严格安全校验器。
 *
 * 防御：
 * 1. 超长二维码载荷 DoS 攻击 (MAX_PAYLOAD_LENGTH)
 * 2. 跨站脚本、代码注入与路径穿越 (SQL/XSS/Traversal 特殊字符排查)
 * 3. 危险/伪造 URI Schema 拦截 (javascript:, file:, http:, content:)
 * 4. 恶意边界设备 ID、溢出与异常 Base64 编码崩溃
 */
object QrPayloadParser {

    const val MAX_PAYLOAD_LENGTH = 2048

    private const val PREFIX_SCHEME = "maodouchat:"
    private const val PREFIX_USER = "maodouchat:user:"
    private const val PREFIX_CHAT = "maodouchat:chat:"
    private const val PREFIX_CHAT_INVITE = "maodouchat:chat-invite:v1:"
    private const val PREFIX_SAFETY = "maodouchat:safety:"

    private val ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    // 邀请 token 清洗收敛至 AppLinkRouter.sanitizeChatInviteToken（与深链同事实源）。

    fun parse(rawText: String?): QrParsedPayload {
        if (rawText == null || rawText.isBlank()) {
            return QrParsedPayload.Invalid("Empty or null QR payload")
        }

        val text = rawText.trim()
        if (text.length > MAX_PAYLOAD_LENGTH) {
            return QrParsedPayload.Invalid("Payload length ${text.length} exceeds maximum allowed ($MAX_PAYLOAD_LENGTH)")
        }

        // 拦截非毛豆应用协议与危险 Scheme
        if (!text.startsWith(PREFIX_SCHEME, ignoreCase = true)) {
            val scheme = text.substringBefore(':', missingDelimiterValue = "").lowercase()
            return QrParsedPayload.Invalid("Unsupported or dangerous scheme: $scheme")
        }

        return when {
            text.startsWith(PREFIX_USER) -> parseUserPayload(text.removePrefix(PREFIX_USER))
            text.startsWith(PREFIX_CHAT) -> parseChatPayload(text.removePrefix(PREFIX_CHAT))
            text.startsWith(PREFIX_CHAT_INVITE) -> parseChatInvitePayload(text.removePrefix(PREFIX_CHAT_INVITE))
            text.startsWith(PREFIX_SAFETY) -> parseSafetyPayload(text.removePrefix(PREFIX_SAFETY))
            else -> QrParsedPayload.Invalid("Unknown maodouchat QR prefix")
        }
    }

    private fun parseUserPayload(rawUserId: String): QrParsedPayload {
        val userId = rawUserId.trim()
        if (!userId.matches(ID_REGEX)) {
            return QrParsedPayload.Invalid("Malformed or dangerous user ID in payload")
        }
        return QrParsedPayload.User(userId)
    }

    private fun parseChatPayload(rawChatId: String): QrParsedPayload {
        val chatId = rawChatId.trim()
        if (!chatId.matches(ID_REGEX)) {
            return QrParsedPayload.Invalid("Malformed or dangerous chat ID in payload")
        }
        return QrParsedPayload.Chat(chatId)
    }

    private fun parseChatInvitePayload(rawToken: String): QrParsedPayload {
        val token = com.maodouchat.ui.navigation.AppLinkRouter.sanitizeChatInviteToken(rawToken)
            ?: return QrParsedPayload.Invalid("Malformed chat invite token in payload")
        return QrParsedPayload.ChatInvite(token)
    }

    private fun parseSafetyPayload(payload: String): QrParsedPayload {
        val parts = payload.split(":")
        return try {
            if (parts.size == 7 && parts[0] == "v2") {
                val ownerUserId = decodePart(parts[1]) ?: return QrParsedPayload.Invalid("Invalid owner user ID in safety v2")
                val ownerDeviceId = parts[2].toIntOrNull()?.takeIf { it in 1..255 }
                    ?: return QrParsedPayload.Invalid("Invalid owner device ID in safety v2")
                val peerUserId = decodePart(parts[3]) ?: return QrParsedPayload.Invalid("Invalid peer user ID in safety v2")
                val peerDeviceId = parts[4].toIntOrNull()?.takeIf { it in 1..255 }
                    ?: return QrParsedPayload.Invalid("Invalid peer device ID in safety v2")
                val ownerFingerprint = decodePart(parts[5])
                    ?: return QrParsedPayload.Invalid("Invalid owner fingerprint in safety v2")
                val peerFingerprint = decodePart(parts[6])
                    ?: return QrParsedPayload.Invalid("Invalid peer fingerprint in safety v2")

                if (!ownerUserId.matches(ID_REGEX) || !peerUserId.matches(ID_REGEX)) {
                    return QrParsedPayload.Invalid("Safety v2 user ID failed format validation")
                }

                QrParsedPayload.Safety(
                    version = 2,
                    ownerUserId = ownerUserId,
                    ownerDeviceId = ownerDeviceId,
                    peerUserId = peerUserId,
                    peerDeviceId = peerDeviceId,
                    ownerIdentityFingerprint = ownerFingerprint,
                    peerIdentityFingerprint = peerFingerprint
                )
            } else if (parts.size == 5) {
                val ownerUserId = decodePart(parts[0]) ?: return QrParsedPayload.Invalid("Invalid owner user ID in safety v1")
                val ownerDeviceId = parts[1].toIntOrNull()?.takeIf { it in 1..255 }
                    ?: return QrParsedPayload.Invalid("Invalid owner device ID in safety v1")
                val peerUserId = decodePart(parts[2]) ?: return QrParsedPayload.Invalid("Invalid peer user ID in safety v1")
                val peerDeviceId = parts[3].toIntOrNull()?.takeIf { it in 1..255 }
                    ?: return QrParsedPayload.Invalid("Invalid peer device ID in safety v1")
                val safetyCode = decodePart(parts[4]) ?: return QrParsedPayload.Invalid("Invalid safety code in safety v1")

                if (!ownerUserId.matches(ID_REGEX) || !peerUserId.matches(ID_REGEX)) {
                    return QrParsedPayload.Invalid("Safety v1 user ID failed format validation")
                }

                QrParsedPayload.Safety(
                    version = 1,
                    ownerUserId = ownerUserId,
                    ownerDeviceId = ownerDeviceId,
                    peerUserId = peerUserId,
                    peerDeviceId = peerDeviceId,
                    safetyCode = safetyCode
                )
            } else {
                QrParsedPayload.Invalid("Unrecognized safety payload segment count (${parts.size})")
            }
        } catch (t: Throwable) {
            QrParsedPayload.Invalid("Failed to parse safety payload: ${t.message}")
        }
    }

    private fun decodePart(value: String): String? = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
