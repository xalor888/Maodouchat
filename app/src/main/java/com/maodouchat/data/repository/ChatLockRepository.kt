package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.ChatLockDao
import com.maodouchat.data.local.entity.ChatLockEntity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ChatLockRepository(private val dao: ChatLockDao) {

    /**
     * PBKDF2 迭代次数。600k 次（OWASP 建议区间）使 4-8 位 PIN 的穷举成本从分钟级
     * 提升到小时级，配合 SQLCipher 主防线和 16 字节随机 salt，达到聊天锁的隐私承诺。
     * 旧 hash 仍按各自存储的迭代数验证，验证成功后自动升级。
     */
    private val pbkdf2Iterations = 600_000

    /** 新格式 PIN 哈希前缀：`pbkdf2$<iter>$<saltHex>$<hashHex>` */
    private val pbkdf2Prefix = "pbkdf2$"

    /** 连续失败 [MAX_FAILURES] 次后锁定 [LOCKOUT_MS]（进程内状态，防暴力尝试）。 */
    private val failuresByChat = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lockedUntilByChat = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private companion object {
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L
    }

    suspend fun get(chatId: String): ChatLockEntity? = dao.get(chatId)

    suspend fun setLock(chatId: String, pin: String): Boolean {
        if (pin.length !in 4..8) return false
        val salt = generateSalt()
        val hash = pbkdf2(pin, salt, pbkdf2Iterations)
        // pinHash 编码完整格式，salt 字段保留以兼容旧 schema 查询
        dao.upsert(ChatLockEntity(chatId, "$pbkdf2Prefix$pbkdf2Iterations\$$salt\$$hash", salt))
        failuresByChat.remove(chatId)
        lockedUntilByChat.remove(chatId)
        return true
    }

    suspend fun verify(chatId: String, pin: String): Boolean {
        val lock = dao.get(chatId) ?: return true
        val now = System.currentTimeMillis()
        val lockedUntil = lockedUntilByChat[chatId] ?: 0L
        if (lockedUntil > now) return false
        val expected = lock.pinHash
        val ok = if (expected.startsWith(pbkdf2Prefix)) {
            // 新格式：pbkdf2$<iter>$<saltHex>$<hashHex>
            val parts = expected.split("$")
            if (parts.size != 4) return false
            val iter = parts[1].toIntOrNull() ?: return false
            val salt = parts[2]
            val storedHash = parts[3]
            val actualHash = pbkdf2(pin, salt, iter)
            val verified = constantTimeEquals(storedHash, actualHash)
            // 迭代数低于当前标准（历史 100k 条目）→ 验证成功后升级
            if (verified && iter < pbkdf2Iterations) {
                try {
                    setLock(chatId, pin)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 升级失败不阻塞当前验证流程
                }
            }
            verified
        } else {
            // 旧格式：SHA-256(pin + salt)。验证成功后立即升级到 PBKDF2
            val actualHash = sha256(pin + lock.salt)
            val oldOk = constantTimeEquals(expected, actualHash)
            if (oldOk) {
                // 升级到 PBKDF2：setLock 是 suspend，不能 runCatching 否则吞 CancellationException；
                // 升级失败不影响本次验证通过（旧 hash 仍可校验），仅记录日志。
                try {
                    setLock(chatId, pin)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 升级失败不阻塞当前验证流程
                }
            }
            oldOk
        }
        if (!ok) {
            val failures = (failuresByChat[chatId] ?: 0) + 1
            if (failures >= MAX_FAILURES) {
                lockedUntilByChat[chatId] = now + LOCKOUT_MS
                failuresByChat.remove(chatId)
            } else {
                failuresByChat[chatId] = failures
            }
            return false
        }
        failuresByChat.remove(chatId)
        lockedUntilByChat.remove(chatId)
        return true
    }

    /** 连续失败退避剩余毫秒；0 表示未锁定。 */
    fun lockoutRemainingMs(chatId: String): Long {
        val until = lockedUntilByChat[chatId] ?: return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    suspend fun remove(chatId: String) = dao.remove(chatId)

    suspend fun listLockedChats(): List<String> = dao.listLockedChatIds()

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pbkdf2(pin: String, saltHex: String, iterations: Int): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
