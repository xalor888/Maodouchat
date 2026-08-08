package com.maodouchat.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * 简易二维码生成：把"maodouchat:user:<userId>" 编码成 300x300 PNG bitmap
 * 使用 ZXing（已在 build.gradle.kts 引入 transitively via JourneyApps/ZXing）
 */
object QrCodeGenerator {

    private const val PREFIX_USER = "maodouchat:user:"
    private const val PREFIX_CHAT = "maodouchat:chat:"
    private const val PREFIX_CHAT_INVITE = "maodouchat:chat-invite:v1:"
    private const val PREFIX_SAFETY = "maodouchat:safety:"

    fun encodeUserQrPayload(userId: String): String = PREFIX_USER + userId
    fun encodeChatQrPayload(chatId: String): String = PREFIX_CHAT + chatId
    fun encodeChatInviteQrPayload(token: String): String = PREFIX_CHAT_INVITE + token.trim()
    fun encodeSafetyQrPayload(
        ownerUserId: String,
        ownerDeviceId: Int,
        peerUserId: String,
        peerDeviceId: Int,
        safetyCode: String
    ): String = PREFIX_SAFETY + listOf(
        encodePart(ownerUserId),
        ownerDeviceId.toString(),
        encodePart(peerUserId),
        peerDeviceId.toString(),
        encodePart(safetyCode)
    ).joinToString(":")

    fun encodeSafetyQrPayload(
        ownerUserId: String,
        ownerDeviceId: Int,
        peerUserId: String,
        peerDeviceId: Int,
        ownerIdentityFingerprint: String,
        peerIdentityFingerprint: String
    ): String = PREFIX_SAFETY + listOf(
        "v2",
        encodePart(ownerUserId),
        ownerDeviceId.toString(),
        encodePart(peerUserId),
        peerDeviceId.toString(),
        encodePart(ownerIdentityFingerprint),
        encodePart(peerIdentityFingerprint)
    ).joinToString(":")

    fun parsePayload(text: String): QrTarget? {
        if (text.isBlank()) return null
        return when {
            text.startsWith(PREFIX_USER) -> QrTarget.User(text.removePrefix(PREFIX_USER))
            text.startsWith(PREFIX_CHAT) -> QrTarget.Chat(text.removePrefix(PREFIX_CHAT))
            text.startsWith(PREFIX_CHAT_INVITE) -> {
                val token = text.removePrefix(PREFIX_CHAT_INVITE).trim()
                if (token.matches(Regex("^[A-Za-z0-9_-]{32,80}$"))) QrTarget.ChatInvite(token) else null
            }
            text.startsWith(PREFIX_SAFETY) -> parseSafetyPayload(text.removePrefix(PREFIX_SAFETY))
            else -> null
        }
    }

    private fun parseSafetyPayload(payload: String): QrTarget.Safety? {
        val parts = payload.split(":")
        if (parts.size == 7 && parts[0] == "v2") {
            val ownerUserId = decodePart(parts[1]) ?: return null
            val ownerDeviceId = parts[2].toIntOrNull()?.takeIf { it in 1..255 } ?: return null
            val peerUserId = decodePart(parts[3]) ?: return null
            val peerDeviceId = parts[4].toIntOrNull()?.takeIf { it in 1..255 } ?: return null
            val ownerFingerprint = decodePart(parts[5]) ?: return null
            val peerFingerprint = decodePart(parts[6]) ?: return null
            return QrTarget.Safety(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = peerDeviceId,
                ownerIdentityFingerprint = ownerFingerprint,
                peerIdentityFingerprint = peerFingerprint
            )
        }
        if (parts.size != 5) return null
        val ownerUserId = decodePart(parts[0]) ?: return null
        val ownerDeviceId = parts[1].toIntOrNull()?.takeIf { it in 1..255 } ?: return null
        val peerUserId = decodePart(parts[2]) ?: return null
        val peerDeviceId = parts[3].toIntOrNull()?.takeIf { it in 1..255 } ?: return null
        val safetyCode = decodePart(parts[4]) ?: return null
        return QrTarget.Safety(ownerUserId, ownerDeviceId, peerUserId, peerDeviceId, safetyCode = safetyCode)
    }

    fun generateBitmap(content: String, size: Int = 600): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()

    sealed class QrTarget {
        data class User(val userId: String) : QrTarget()
        data class Chat(val chatId: String) : QrTarget()
        data class ChatInvite(val token: String) : QrTarget()
        data class Safety(
            val ownerUserId: String,
            val ownerDeviceId: Int,
            val peerUserId: String,
            val peerDeviceId: Int,
            val safetyCode: String? = null,
            val ownerIdentityFingerprint: String? = null,
            val peerIdentityFingerprint: String? = null
        ) : QrTarget()
    }

    @Suppress("unused")
    private fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun encodePart(value: String): String =
        Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decodePart(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
