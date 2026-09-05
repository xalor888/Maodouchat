package com.maodouchat.server.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 登录尝试门禁（B02：自 `Routing.kt` 局部函数 + `AuthRouting.kt` 内联检查抽出）。
 *
 * 单账号连续失败锁定：5 次失败锁定「该账号 + 该源 IP」15 分钟。
 * 8.51 修复 M1：锁定 key 加入源 IP——攻击者源 IP 的失败只锁该组合，
 * 受害者从自己 IP 登录不受远程锁定影响（可用性 DoS 缓解）。
 * 8.40：锁定期满即清除失败计数，否则攻击者周期性错 1 次即可无限期锁死账号。
 *
 * @param clock 可注入时钟（单测用假时钟；生产默认系统时间）。
 */
class LoginAttemptGate(
    private val maxFails: Int = MAX_FAILS,
    private val lockMs: Long = LOCK_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Lockout(var fails: Int, var lockUntil: Long, var lastFailureAt: Long = 0L)

    private val lockouts = ConcurrentHashMap<String, Lockout>()
    private val lastSweepAt = AtomicLong(0L)

    fun key(emailKey: String, ip: String): String = "$emailKey|$ip"

    fun isLocked(key: String, now: Long = clock()): Boolean =
        lockouts[key]?.lockUntil?.let { it > now } ?: false

    /**
     * 仅清除「确实锁定过且已过期」的条目：lockUntil=0 表示从未锁定，不得移除，
     * 否则每次失败后计数被清空、锁定永远不会触发。
     */
    fun clearIfExpired(key: String, now: Long = clock()) {
        val lock = lockouts[key] ?: return
        if (lock.lockUntil > 0L && lock.lockUntil <= now) lockouts.remove(key)
    }

    /** 登录成功：清除失败计数（按 IP 隔离），避免历史失败触发误锁。 */
    fun clear(key: String) {
        lockouts.remove(key)
    }

    /** 记录一次登录失败；达到阈值则锁定。成功登录后由调用方 [clear]。 */
    fun recordFailure(emailKey: String, ip: String, now: Long = clock()) {
        sweep(now)
        lockouts.compute(key(emailKey, ip)) { _, existing ->
            val lock = existing ?: Lockout(0, 0L, now)
            lock.lastFailureAt = now
            lock.fails += 1
            if (lock.fails >= maxFails) {
                lock.lockUntil = now + lockMs
                // 8.31 运维修复 HIGH：账号锁定是安全事件，必须留应用日志（此前仅内存计数）
                securityLogger.warn(
                    "Login account locked [emailKey={} ip={}] after {} failures for {}ms",
                    emailKey, ip, maxFails, lockMs,
                )
            }
            lock
        }
    }

    /** 周期清理过期锁定条目，避免内存无界增长（最多 1 分钟执行一次）。 */
    fun sweep(now: Long = clock()) {
        val lastSweep = lastSweepAt.get()
        if (now - lastSweep > SWEEP_INTERVAL_MS && lastSweepAt.compareAndSet(lastSweep, now)) {
            val staleCutoff = now - lockMs - SWEEP_GRACE_MS
            lockouts.entries.removeIf { it.value.lastFailureAt <= staleCutoff }
        }
    }

    /** 仅供单测观察。 */
    internal fun snapshotSize(): Int = lockouts.size

    companion object {
        const val MAX_FAILS = 5
        const val LOCK_MS = 15L * 60L * 1000L
        private const val SWEEP_INTERVAL_MS = 60_000L
        private const val SWEEP_GRACE_MS = 60_000L
        private val securityLogger = org.slf4j.LoggerFactory.getLogger("LoginSecurity")
    }
}
