package com.maodouchat.domain.messaging

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// 假聊天模式的纯决策：前台拦截判定 + 解锁 PIN 的格式、弱 PIN 拒绝与 PBKDF2 存取。
// prefs/IO、失败计数与锁定、旧明文迁移仍在 app 侧 FakeChatManager；这里只收不碰 Android 的纯逻辑。
object FakeChatPolicy {

    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 12
    private const val PBKDF2_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val PBKDF2_KEY_BITS = 256

    // SecureRandom 线程安全，每次设 PIN 都 new 会反复播种，对象级复用。
    private val secureRandom = SecureRandom()

    // SecretKeyFactory 线程安全，getInstance 每次都要走 provider 查找，对象级复用。
    private val pbkdf2Factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

    fun shouldShowFake(
        enabled: Boolean,
        hasPin: Boolean,
        unlockedForCurrentUser: Boolean,
        relockOnBackground: Boolean,
        backgroundAtMillis: Long
    ): Boolean {
        if (!enabled || !hasPin) return false
        if (unlockedForCurrentUser) {
            if (!relockOnBackground) return false
            if (backgroundAtMillis <= 0L) return false
        }
        return true
    }

    // 4–12 位纯数字。
    fun isPinValid(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)

    // 默认弱密码一律拒绝。
    fun isWeakPin(pin: String): Boolean = pin == "0000" || pin == "1234"

    // 生成带盐存储串：iterations:b64(salt):b64(hash)。
    fun encodePinHash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = pbkdf2(pin, salt, PBKDF2_ITERATIONS)
        return "$PBKDF2_ITERATIONS:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(hash)}"
    }

    // 校验 PIN 与存储串；格式不对一律判失败。旧明文存储的迁移留在 app 侧。
    fun verifyPinHash(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val actual = pbkdf2(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, PBKDF2_KEY_BITS)
        return try {
            pbkdf2Factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
