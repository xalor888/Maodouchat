package com.maodouchat.server.service

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * RFC 6238 TOTP (SHA-1, 30s, 6 digits) without external dependencies.
 */
object TotpService {
    private val random = SecureRandom()
    private const val PERIOD_SEC = 30L
    private const val DIGITS = 6
    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // 8.46 修复：TOTP 重放保护——记录每个 secret 最近验证成功的 counter。
    // 否则同一 code 在 window=1（90s）内可被反复用于登录（攻击者窃取密码+code 可重放，
    // 登录路径的失败锁定对"连续成功"无效）。key 用 base32 secret（服务端本就明文存 DB）。
    // 8.51 修复 M2：内存态仅为兜底；权威重放保护由调用方经 [onAcceptedCounter] 持久化到 DB
    //（重启/多实例后不可重放）。
    private val lastVerifiedCounter = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun generateSecret(bytes: Int = 20): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return base32Encode(raw)
    }

    fun provisioningUri(secretBase32: String, accountEmail: String, issuer: String = "毛豆聊天"): String {
        val email = accountEmail.trim()
        val label = java.net.URLEncoder.encode("$issuer:$email", Charsets.UTF_8)
        val iss = java.net.URLEncoder.encode(issuer, Charsets.UTF_8)
        return "otpauth://totp/$label?secret=${secretBase32.trim()}&issuer=$iss&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SEC"
    }

    /**
     * 校验 TOTP code。
     * @param onAcceptedCounter 匹配到 counter 后由调用方判定是否接受（DB 持久化原子 CAS）；
     *   返回 false 则整体拒绝（重放保护权威层）。默认恒接受（保持兼容）。
     */
    fun verify(
        secretBase32: String,
        code: String,
        nowMs: Long = System.currentTimeMillis(),
        window: Int = 1,
        onAcceptedCounter: (Long) -> Boolean = { true }
    ): Boolean {
        val normalized = code.trim().filter { it.isDigit() }
        if (normalized.length != DIGITS) return false
        val secret = base32Decode(secretBase32) ?: return false
        val counter = nowMs / 1000L / PERIOD_SEC
        for (w in -window..window) {
            val expected = generateCode(secret, counter + w)
            if (constantTimeEquals(expected, normalized)) {
                // 先持久化（DB 原子 CAS），拒绝则整体拒绝；再内存 merge 兜底（进程内快速拒绝）
                if (!onAcceptedCounter(counter + w)) return false
                val guardKey = secretBase32.trim()
                val accepted = lastVerifiedCounter.merge(guardKey, counter + w) { old, new -> maxOf(old, new) }
                return accepted == counter + w
            }
        }
        return false
    }

    private fun generateCode(secret: ByteArray, counter: Long): String {
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 10.0.pow(DIGITS).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    private fun base32Encode(data: ByteArray): String {
        var buffer = 0
        var bitsLeft = 0
        val out = StringBuilder()
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val idx = (buffer shr (bitsLeft - 5)) and 0x1f
                bitsLeft -= 5
                out.append(BASE32_ALPHABET[idx])
            }
        }
        if (bitsLeft > 0) {
            val idx = (buffer shl (5 - bitsLeft)) and 0x1f
            out.append(BASE32_ALPHABET[idx])
        }
        return out.toString()
    }

    private fun base32Decode(input: String): ByteArray? {
        val cleaned = input.trim().uppercase().replace("=", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>()
        for (ch in cleaned) {
            val valIdx = BASE32_ALPHABET.indexOf(ch)
            if (valIdx < 0) return null
            buffer = (buffer shl 5) or valIdx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
