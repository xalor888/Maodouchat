package com.maodouchat.server.service

import com.maodouchat.server.model.MessageResponse

/**
 * Sealed-sender *style* delivery helpers.
 *
 * Full Signal sealed-sender hides sender identity until ciphertext decrypts. We cannot
 * redact [MessageResponse.senderId] on the chat fan-out path without breaking libsignal
 * session addressing (decrypt needs the real peer id).
 *
 * Therefore:
 * - Chat WS/REST history always keeps the real senderId for participants (crypto-safe).
 * - Push notifications and bot webhooks may use [REDACTED_SENDER] when sealedSender=true.
 * - [forViewer] is a no-op for participant history (reserved for future multi-hop relays).
 */
object SealedSenderDelivery {
    const val REDACTED_SENDER = "sealed"

    fun forViewer(message: MessageResponse, viewerId: String): MessageResponse {
        // Intentionally keep senderId for all chat participants so E2EE decrypt works.
        // Sealed flag is still exposed for UI / analytics; push layer redacts separately.
        return message
    }

    fun pushSenderId(realSenderId: String, sealed: Boolean): String =
        if (sealed) REDACTED_SENDER else realSenderId

    fun webhookSenderId(realSenderId: String?, sealed: Boolean): String? =
        when {
            realSenderId.isNullOrBlank() -> realSenderId
            sealed -> REDACTED_SENDER
            else -> realSenderId
        }

    fun authorize(
        requested: Boolean,
        certificateHeader: String?,
        certificateBody: String?,
        userId: String,
        deviceId: Int = 0
    ): Boolean {
        if (!requested) return false
        if (!RuntimeConfigService.isSealedSenderEnabled()) return false
        val cert = certificateHeader?.takeIf { it.isNotBlank() }
            ?: certificateBody?.takeIf { it.isNotBlank() }
            ?: return false
        val verified = SealedSenderCertificateService.verify(cert) ?: return false
        if (verified.userId != userId) return false
        // 8.46 修复：deviceId 参数此前被完全忽略。当前 WS/REST 协议未携带发送设备 ID，
        // 因此默认（deviceId=0）退化为只验 >0（与历史一致，不误拒多设备）；
        // 未来协议携带发送设备时，调用方可传具体 deviceId 做严格绑定，防止
        // 任一已确认设备以其他设备身份发 sealed 消息。
        if (verified.deviceId < 1) return false
        return if (deviceId > 0) verified.deviceId == deviceId else verified.deviceId >= 1
    }
}
