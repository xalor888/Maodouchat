package com.maodouchat.security.pin

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 统一 PIN 锁安全策略：PBKDF2 哈希、旧版 SHA-256 透明升级、失败退避限流、内存解锁会话缓存。
 */
object PinSecurityPolicy {
    // SecureRandom 线程安全，每次生成 salt 都 new 会反复播种，对象级复用。
    private val secureRandom = SecureRandom()

    // SecretKeyFactory 线程安全，getInstance 每次都要走 provider 查找，对象级复用。
    private val pbkdf2Factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

    // MessageDigest 非线程安全，ThreadLocal 每线程复用一个实例；每次使用都以 digest() 收尾（按规范自动重置），与 new 等价。
    private val sha256Digest: ThreadLocal<MessageDigest> =
        ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 12

    const val DEFAULT_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val PBKDF2_PREFIX = "pbkdf2$"

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)

    fun generateSalt(byteCount: Int = SALT_BYTES): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPbkdf2(pin: String, saltHex: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val bytes = pbkdf2Factory.generateSecret(spec).encoded
        spec.clearPassword()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashSha256Legacy(pin: String, salt: String): String {
        val md = sha256Digest.get()
        val bytes = md.digest((pin + salt).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 恒定时间比较，抵御时序攻击 */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * 编码完整的 PBKDF2 存储字符串：pbkdf2$<iterations>$<saltHex>$<hashHex>
     */
    fun encodeStoredHash(pin: String, saltHex: String = generateSalt(), iterations: Int = DEFAULT_ITERATIONS): StoredPinHash {
        val hash = hashPbkdf2(pin, saltHex, iterations)
        val formatted = "$PBKDF2_PREFIX$iterations\$$saltHex\$$hash"
        return StoredPinHash(
            formattedString = formatted,
            saltHex = saltHex,
            hashHex = hash,
            iterations = iterations,
            isPbkdf2 = true
        )
    }

    /**
     * 校验 PIN 并检测是否需要从旧版本（SHA-256 或低轮数 PBKDF2）升级
     */
    fun verifyPin(
        pin: String,
        storedHash: String,
        storedSalt: String = "",
        minDesiredIterations: Int = DEFAULT_ITERATIONS
    ): VerificationOutcome {
        if (storedHash.isBlank()) return VerificationOutcome.Corrupted

        if (storedHash.startsWith(PBKDF2_PREFIX)) {
            val parts = storedHash.split("$")
            if (parts.size != 4) return VerificationOutcome.Corrupted
            val iter = parts[1].toIntOrNull() ?: return VerificationOutcome.Corrupted
            val salt = parts[2]
            val expectedHash = parts[3]
            val computedHash = hashPbkdf2(pin, salt, iter)
            val matched = constantTimeEquals(expectedHash, computedHash)
            if (!matched) return VerificationOutcome.Mismatch
            val needsUpgrade = iter < minDesiredIterations
            return VerificationOutcome.Success(
                needsUpgrade = needsUpgrade,
                upgradedHash = if (needsUpgrade) encodeStoredHash(pin, generateSalt(), minDesiredIterations) else null
            )
        } else {
            // 兼容旧版 SHA-256(pin + salt)
            val computedHash = hashSha256Legacy(pin, storedSalt)
            val matched = constantTimeEquals(storedHash, computedHash)
            if (!matched) return VerificationOutcome.Mismatch
            // 校验成功，强制提示升级为 PBKDF2
            return VerificationOutcome.Success(
                needsUpgrade = true,
                upgradedHash = encodeStoredHash(pin, generateSalt(), minDesiredIterations)
            )
        }
    }

}
