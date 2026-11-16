package com.maodouchat.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.maodouchat.core.model.QrParsedPayload
import com.maodouchat.core.model.QrPayloadParser
import com.maodouchat.core.model.QrTarget
import com.maodouchat.domain.contacts.QrPayloadCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.util.Base64

/**
 * 简易二维码生成：把"maodouchat:user:<userId>" 编码成 300x300 PNG bitmap
 * 使用 ZXing（已在 build.gradle.kts 引入 transitively via JourneyApps/ZXing）
 */
object QrCodeGenerator {

    fun encodeUserQrPayload(userId: String): String = QrPayloadCodec.encodeUserQrPayload(userId)
    fun encodeChatQrPayload(chatId: String): String = QrPayloadCodec.encodeChatQrPayload(chatId)
    fun encodeChatInviteQrPayload(token: String): String = QrPayloadCodec.encodeChatInviteQrPayload(token)
    fun encodeSafetyQrPayload(
        ownerUserId: String,
        ownerDeviceId: Int,
        peerUserId: String,
        peerDeviceId: Int,
        safetyCode: String
    ): String = QrPayloadCodec.encodeSafetyQrPayload(ownerUserId, ownerDeviceId, peerUserId, peerDeviceId, safetyCode)

    fun encodeSafetyQrPayload(
        ownerUserId: String,
        ownerDeviceId: Int,
        peerUserId: String,
        peerDeviceId: Int,
        ownerIdentityFingerprint: String,
        peerIdentityFingerprint: String
    ): String = QrPayloadCodec.encodeSafetyQrPayload(
        ownerUserId, ownerDeviceId, peerUserId, peerDeviceId, ownerIdentityFingerprint, peerIdentityFingerprint
    )

    fun parsePayload(text: String): QrTarget? {
        return when (val parsed = QrPayloadParser.parse(text)) {
            is QrParsedPayload.User -> QrTarget.User(parsed.userId)
            is QrParsedPayload.Chat -> QrTarget.Chat(parsed.chatId)
            is QrParsedPayload.ChatInvite -> QrTarget.ChatInvite(parsed.token)
            is QrParsedPayload.Safety -> QrTarget.Safety(
                ownerUserId = parsed.ownerUserId,
                ownerDeviceId = parsed.ownerDeviceId,
                peerUserId = parsed.peerUserId,
                peerDeviceId = parsed.peerDeviceId,
                safetyCode = parsed.safetyCode,
                ownerIdentityFingerprint = parsed.ownerIdentityFingerprint,
                peerIdentityFingerprint = parsed.peerIdentityFingerprint
            )
            is QrParsedPayload.Invalid -> null
        }
    }

    fun generateBitmap(content: String, size: Int = 600): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp[x, y] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bmp
    }.getOrNull()


    @Suppress("unused")
    private fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
}
