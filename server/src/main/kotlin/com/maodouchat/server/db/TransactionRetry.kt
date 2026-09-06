package com.maodouchat.server.db

import java.sql.SQLException

/**
 * 事务串行化重试（B02 注销可重试编排器的第一步）。
 *
 * 大事务（注销清 30+ 表）在并发下可能吃数据库死锁/串行化失败
 * （PG `40P01`/`40001`，H2 `40001`）：回滚后整体重跑是安全的
 * （被删行有 `deletedAt` 守卫，重复跑幂等），仅此类异常值得重试；
 * 唯一冲突/约束违反等业务错误直接透出。
 */
fun <T> withSerializationRetry(maxAttempts: Int = 3, block: () -> T): T {
    require(maxAttempts >= 1)
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (error: Exception) {
            attempt++
            if (attempt >= maxAttempts || !isSerializationFailure(error)) throw error
            Thread.sleep(retryBackoffMs(attempt))
        }
    }
}

fun isSerializationFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SQLException && (current.sqlState == "40001" || current.sqlState == "40P01")) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun retryBackoffMs(attempt: Int): Long = 50L * (1L shl attempt.coerceAtMost(4))
