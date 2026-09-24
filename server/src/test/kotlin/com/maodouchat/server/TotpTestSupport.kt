package com.maodouchat.server

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * 测试侧的 RFC 6238 TOTP 生成器（SHA-1 / 30s / 6 位），与服务端 `TotpService.generateCode`
 * 同算法，用来在测试里独立算出「此刻的正确验证码」。
 *
 * 原先 `TotpFlowRouteTest` 与 `repository/AuthTokenAndProfileFixTest` 各持一份**逐字节相同**
 * 的私有副本；管理后台二次校验的测试需要第三份时抽到这里，而不是再复制一次。
 */
internal const val TOTP_PERIOD_SEC = 30L
internal const val TOTP_DIGITS = 6

/** 按 [nowMs]（默认当前时刻）算出该 secret 的 6 位验证码。 */
internal fun testTotpCode(secretBase32: String, nowMs: Long = System.currentTimeMillis()): String {
    val cleaned = secretBase32.trim().uppercase().replace("=", "").replace(" ", "")
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var buffer = 0
    var bitsLeft = 0
    val out = ArrayList<Byte>()
    for (ch in cleaned) {
        val idx = alphabet.indexOf(ch)
        check(idx >= 0) { "invalid base32 char: $ch" }
        buffer = (buffer shl 5) or idx
        bitsLeft += 5
        if (bitsLeft >= 8) {
            out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
            bitsLeft -= 8
        }
    }
    val counter = nowMs / 1000L / TOTP_PERIOD_SEC
    val data = ByteBuffer.allocate(8).putLong(counter).array()
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(out.toByteArray(), "HmacSHA1"))
    val hash = mac.doFinal(data)
    val offset = hash.last().toInt() and 0x0f
    val binary =
        ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
    val otp = binary % 10.0.pow(TOTP_DIGITS).toInt()
    return otp.toString().padStart(TOTP_DIGITS, '0')
}
