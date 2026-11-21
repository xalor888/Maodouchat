package com.maodouchat.data.repository

import com.maodouchat.core.session.RateLimiter
import com.maodouchat.core.session.VerificationOutcome
import com.maodouchat.data.local.dao.ChatLockDao
import com.maodouchat.data.local.entity.ChatLockEntity
import com.maodouchat.domain.messaging.ChatLockPinPolicy
import kotlinx.coroutines.CancellationException

// 聊天锁 PIN 的 DAO 薄壳：密码学与限流核在 :domain:messaging 的
// ChatLockPinPolicy（原语复用 :core:session），这里只剩 Room 往返与
// 失败/锁定态的持有。公开 API 不变，调用方零改动。
class ChatLockRepository(
    private val dao: ChatLockDao,
    private val limiter: RateLimiter = ChatLockPinPolicy.newLimiter(),
) {

    suspend fun get(chatId: String): ChatLockEntity? = dao.get(chatId)

    suspend fun setLock(chatId: String, pin: String): Boolean {
        if (!ChatLockPinPolicy.isSettablePin(pin)) return false
        val stored = ChatLockPinPolicy.encodeNewLock(pin)
        dao.upsert(ChatLockEntity(chatId, stored.formattedString, stored.saltHex))
        limiter.reset(chatId)
        return true
    }

    suspend fun verify(chatId: String, pin: String): Boolean {
        val lock = dao.get(chatId) ?: return true
        if (limiter.isLocked(chatId)) return false
        // 空 hash 视同一次失败尝试：旧实现走 SHA-256 分支比对失败计一次。
        if (lock.pinHash.isBlank()) {
            limiter.recordFailure(chatId)
            return false
        }
        return when (val outcome = ChatLockPinPolicy.verifyStoredPin(pin, lock.pinHash, lock.salt)) {
            is VerificationOutcome.Success -> {
                // 升级写回仍走 setLock 的长度校验：旧实现经 setLock 写回，
                // 长度不合规的 PIN 不升级（但本次验证仍通过）。
                if (outcome.needsUpgrade && ChatLockPinPolicy.isSettablePin(pin)) {
                    outcome.upgradedHash?.let { upgraded ->
                        try {
                            dao.upsert(ChatLockEntity(chatId, upgraded.formattedString, upgraded.saltHex))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 升级失败不阻塞当前验证流程
                        }
                    }
                }
                limiter.recordSuccess(chatId)
                true
            }
            VerificationOutcome.Mismatch -> {
                limiter.recordFailure(chatId)
                false
            }
            // 畸形新格式：拒而不计失败，旧实现在格式解析处直接 return false。
            VerificationOutcome.Corrupted -> false
        }
    }

    /** 连续失败退避剩余毫秒；0 表示未锁定。 */
    fun lockoutRemainingMs(chatId: String): Long = limiter.remainingLockoutMs(chatId)

    suspend fun remove(chatId: String) = dao.remove(chatId)

    suspend fun listLockedChats(): List<String> = dao.listLockedChatIds()
}
