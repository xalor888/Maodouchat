package com.maodouchat.core.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单飞 token 刷新（A02）：并发 401 只发起一次真正刷新。
 *
 * 用互斥锁串行化刷新，并在 [successCooldownMillis] 窗口内复用最近一次成功结果：
 * - 并发 N 个 401 只触发 1 次底层 [onRefresh]；
 * - 刷新失败不缓存，下次调用会重试。
 */
class TokenRefreshSingleFlight(
    private val onRefresh: suspend () -> Boolean,
    private val successCooldownMillis: Long = 1_000L,
) {
    private val mutex = Mutex()
    private var lastSuccessAt = Long.MIN_VALUE / 2

    suspend fun refresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        mutex.withLock {
            if (nowMillis - lastSuccessAt < successCooldownMillis) return@withLock true
            val ok = onRefresh()
            if (ok) lastSuccessAt = nowMillis
            ok
        }
}
