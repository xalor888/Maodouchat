package com.maodouchat.contacts

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
