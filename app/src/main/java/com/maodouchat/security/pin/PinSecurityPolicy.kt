package com.maodouchat.security.pin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 统一 PIN 锁安全策略引擎。
 *
 * 提供：
 * 1. PBKDF2 (600,000 轮) + 16 字节安全随机盐加密；
 * 2. 旧版 SHA-256 哈希透明校验并提示升级；
 * 3. 恒定时间比较，抵抗时序侧信道攻击；
 * 4. 连续失败阶梯退避与限流（防止暴力穷举）；
 * 5. 内存级解锁会话缓存（TTL 自动失效与账号隔离）。
 */
object PinSecurityPolicy {
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 12

    const val DEFAULT_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val PBKDF2_PREFIX = "pbkdf2$"

    const val DEFAULT_MAX_FAILURES = 5
    const val DEFAULT_LOCKOUT_MS = 30_000L
    const val DEFAULT_SESSION_TTL_MS = 5 * 60_000L

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)

    fun generateSalt(byteCount: Int = SALT_BYTES): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPbkdf2(pin: String, saltHex: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashSha256Legacy(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    sealed interface VerificationOutcome {
        data class Success(val needsUpgrade: Boolean, val upgradedHash: StoredPinHash? = null) : VerificationOutcome
        object Mismatch : VerificationOutcome
        object Corrupted : VerificationOutcome
    }

    data class StoredPinHash(
        val formattedString: String,
        val saltHex: String,
        val hashHex: String,
        val iterations: Int,
        val isPbkdf2: Boolean
    )

    /**
     * PIN 尝试限流与阶梯退避器
     */
    class RateLimiter(
        private val maxFailures: Int = DEFAULT_MAX_FAILURES,
        private val lockoutMs: Long = DEFAULT_LOCKOUT_MS,
        private val clock: () -> Long = System::currentTimeMillis
    ) {
        private val failureCounts = ConcurrentHashMap<String, Int>()
        private val lockedUntil = ConcurrentHashMap<String, Long>()

        fun isLocked(key: String): Boolean = remainingLockoutMs(key) > 0L

        fun remainingLockoutMs(key: String): Long {
            val until = lockedUntil[key] ?: return 0L
            val remaining = until - clock()
            return if (remaining > 0L) remaining else 0L
        }

        fun recordFailure(key: String): Long {
            val now = clock()
            val failures = (failureCounts[key] ?: 0) + 1
            if (failures >= maxFailures) {
                val until = now + lockoutMs
                lockedUntil[key] = until
                failureCounts.remove(key)
                return lockoutMs
            } else {
                failureCounts[key] = failures
                return 0L
            }
        }

        fun recordSuccess(key: String) {
            failureCounts.remove(key)
            lockedUntil.remove(key)
        }

        fun reset(key: String) {
            failureCounts.remove(key)
            lockedUntil.remove(key)
        }

        fun resetAll() {
            failureCounts.clear()
            lockedUntil.clear()
        }
    }

    /**
     * 内存级认证会话缓存（支持 TTL 自动失效与防跨账号泄露）
     */
    class AuthenticatedSessionCache(
        private val ttlMs: Long = DEFAULT_SESSION_TTL_MS,
        private val clock: () -> Long = System::currentTimeMillis
    ) {
        private data class CacheEntry(val userId: String, val expiryMillis: Long)
        private val sessions = ConcurrentHashMap<String, CacheEntry>()

        fun markAuthenticated(targetKey: String, userId: String) {
            if (userId.isBlank()) return
            sessions[targetKey] = CacheEntry(userId, clock() + ttlMs)
        }

        fun isAuthenticated(targetKey: String, userId: String): Boolean {
            if (userId.isBlank()) return false
            val entry = sessions[targetKey] ?: return false
            if (entry.userId != userId) return false
            if (clock() >= entry.expiryMillis) {
                sessions.remove(targetKey)
                return false
            }
            return true
        }

        fun invalidate(targetKey: String) {
            sessions.remove(targetKey)
        }

        fun invalidateUser(userId: String) {
            sessions.entries.removeIf { it.value.userId == userId }
        }

        fun invalidateAll() {
            sessions.clear()
        }
    }
}
