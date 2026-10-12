package com.maodouchat.server.plugins

import com.maodouchat.server.model.DevicePreKeyBundleResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.PreKeyBundleResponse
import com.maodouchat.server.model.UploadKeysRequest
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.DeviceBundle
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * Signal 密钥 helper 簇（从 RoutingHelpers.kt 按域拆出，零行为改动）。
 */

// ── 验证辅助 ──────────────────────────

internal fun String.isValidBase64Field(maxLength: Int): Boolean {
    if (isBlank() || length > maxLength) return false
    return all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
}

internal fun UploadKeysRequest.isValid(): Boolean {
    // 9.138：registrationId 收紧到 libsignal 非扩展区间（客户端 generateRegistrationId(false) 生成 1..16380）
    return registrationId in 1..16_380 &&
        deviceId in 1..255 &&
        // 9.312：Signed PreKey ID 同样必须 1..16_777_215（与 PreKey / libsignal 24-bit 一致）
        signedPreKeyId in 1..16_777_215 &&
        identityKey.isValidBase64Field(maxLength = 4096) &&
        signedPreKey.isValidBase64Field(maxLength = 4096) &&
        signedPreKeySignature.isValidBase64Field(maxLength = 4096) &&
        // 签名长度合理性检查：Ed25519 签名 = 64 字节 -> Base88 字符；宽松到 64..512
        signedPreKeySignature.length in 64..512 &&
        (deviceName == null || (deviceName.trim().isNotBlank() && deviceName.length <= 50)) &&
        // 最少 10 个 PreKey：确保客户端有足够一次性密钥，避免频繁拉取
        preKeys.size in 10..100 &&
        preKeys.map { it.keyId }.distinct().size == preKeys.size &&
        // PreKey ID 范围：1..16777215（Signal 协议上限）
        preKeys.all { it.keyId in 1..16_777_215 && it.publicKey.isValidBase64Field(maxLength = 4096) }
}

// ── Signal 密钥辅助 ───────────────────

internal fun DeviceBundle.toPreKeyBundleResponse(): PreKeyBundleResponse = PreKeyBundleResponse(
    registrationId = registrationId,
    deviceId = deviceId,
    identityKey = identityKey,
    signedPreKeyId = signedPreKeyId,
    signedPreKey = signedPreKey,
    signedPreKeySignature = signedPreKeySignature,
    preKeyId = preKeyId,
    preKey = preKey
)

internal fun DeviceBundle.toDevicePreKeyBundleResponse(): DevicePreKeyBundleResponse = DevicePreKeyBundleResponse(
    userId = userId,
    deviceId = deviceId,
    registrationId = registrationId,
    identityKey = identityKey,
    signedPreKeyId = signedPreKeyId,
    signedPreKey = signedPreKey,
    signedPreKeySignature = signedPreKeySignature,
    preKeyId = preKeyId,
    preKey = preKey
)

internal suspend fun ApplicationCall.canFetchKeys(
    requesterId: String,
    targetUserId: String,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchTracker: BoundedRateLimiter,
    allowSelf: Boolean = false
): Boolean {
    if (targetUserId == requesterId) {
        if (allowSelf) return true
        respond(HttpStatusCode.BadRequest, ErrorResponse("不能获取自己的密钥包"))
        return false
    }
    if (!conversationQueryRepository.shareConversation(requesterId, targetUserId)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("只能获取会话参与者的密钥包"))
        return false
    }
    if (!preKeyFetchTracker.allowPreKeyFetch(requesterId, targetUserId)) {
        respond(HttpStatusCode.TooManyRequests, ErrorResponse("请求过于频繁"))
        return false
    }
    return true
}

/**
 * Line-anchored, port-aware SDP video detection. Mirrors the client-side
 * [com.maodouchat.webrtc.CallType.detectFromSdp] so an audio-only call is not
 * misclassified as video (which would wrongly show the video UI / notification).
 * A bare `contains("m=video")` also matches attribute lines (e.g. `a=...`) and a
 * disabled `m=video 0 ...` (port 0), so we require a media line whose port > 0.
 */
fun sdpHasActiveVideo(sdp: String?): Boolean {
    if (sdp.isNullOrBlank()) return false
    return sdp.lineSequence().any { line ->
        val t = line.trimStart()
        if (!t.startsWith("m=video", ignoreCase = true)) return@any false
        val port = t.substring(7).trimStart().takeWhile { it.isDigit() }
        port.toIntOrNull()?.let { it > 0 } ?: false
    }
}
