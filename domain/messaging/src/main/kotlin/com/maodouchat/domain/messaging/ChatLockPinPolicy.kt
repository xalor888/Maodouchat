package com.maodouchat.domain.messaging

import com.maodouchat.core.session.DEFAULT_LOCKOUT_MS
import com.maodouchat.core.session.DEFAULT_MAX_FAILURES
import com.maodouchat.core.session.PinSecurityPolicy
import com.maodouchat.core.session.RateLimiter
import com.maodouchat.core.session.StoredPinHash
import com.maodouchat.core.session.VerificationOutcome

// 聊天锁 PIN 的密码学与限流核。app 侧 ChatLockRepository 的手写 PBKDF2
// （600k 迭代、pbkdf2$ 格式、旧 SHA-256 透明升级、恒定时间比较）与 5 次
// 失败锁 30 秒的进程内限流，与 :core:session 的 PinSecurityPolicy /
// RateLimiter 逐字同构；这里只做组合编排，不再复制密码学代码。
object ChatLockPinPolicy {

    const val MIN_PIN_LENGTH: Int = 4
    const val MAX_PIN_LENGTH: Int = 8

    // 与 :core:session 的 RateLimiter 默认值一致。
    const val MAX_FAILURES: Int = DEFAULT_MAX_FAILURES
    const val LOCKOUT_MS: Long = DEFAULT_LOCKOUT_MS

    // repo 层语义：只校验长度 4..8，不限数字。生产入口的数字门在
    // ChatPinUnlockPolicy.isValidPin（setChatLockPin 先过该门）。
    fun isSettablePin(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH

    fun encodeNewLock(pin: String): StoredPinHash =
        PinSecurityPolicy.encodeStoredHash(pin)

    fun verifyStoredPin(pin: String, storedHash: String, storedSalt: String): VerificationOutcome =
        PinSecurityPolicy.verifyPin(pin, storedHash, storedSalt)

    fun newLimiter(clock: () -> Long = System::currentTimeMillis): RateLimiter =
        RateLimiter(MAX_FAILURES, LOCKOUT_MS, clock)
}
