package com.maodouchat.domain.contacts

import java.nio.charset.StandardCharsets

// 二维码 payload 编码：把 userId/chatId/邀请 token/安全码拼成 maodouchat:<kind>:... 文本。
// 解析侧在 :domain:contacts 的 QrPayloadParser；生成 Bitmap 仍在 app 侧 QrCodeGenerator。
object QrPayloadCodec {
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

    private fun encodePart(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
