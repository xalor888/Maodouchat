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
        return when (val parsed = com.maodouchat.contacts.QrPayloadParser.parse(text)) {
            is com.maodouchat.contacts.QrParsedPayload.User -> QrTarget.User(parsed.userId)
            is com.maodouchat.contacts.QrParsedPayload.Chat -> QrTarget.Chat(parsed.chatId)
            is com.maodouchat.contacts.QrParsedPayload.ChatInvite -> QrTarget.ChatInvite(parsed.token)
            is com.maodouchat.contacts.QrParsedPayload.Safety -> QrTarget.Safety(
                ownerUserId = parsed.ownerUserId,
                ownerDeviceId = parsed.ownerDeviceId,
                peerUserId = parsed.peerUserId,
                peerDeviceId = parsed.peerDeviceId,
                safetyCode = parsed.safetyCode,
                ownerIdentityFingerprint = parsed.ownerIdentityFingerprint,
                peerIdentityFingerprint = parsed.peerIdentityFingerprint
            )
            is com.maodouchat.contacts.QrParsedPayload.Invalid -> null
        }
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
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
