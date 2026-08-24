package com.maodouchat.ui.screen.explore

/**
 * 动态/附近等相对时间：刚发出或模拟器时钟略快时，Android DateUtils
 * 会给出「0分钟后」。过去 1 分钟内、以及未来 2 分钟内的时钟偏差都显示「刚刚」。
 */
object RelativeTimePolicy {
    const val PAST_JUST_NOW_MS = 60_000L
    const val FUTURE_GRACE_MS = 120_000L

    fun shouldUseJustNow(
        timestamp: Long,
        nowMs: Long,
        pastWindowMs: Long = PAST_JUST_NOW_MS,
        futureGraceMs: Long = FUTURE_GRACE_MS,
    ): Boolean {
        if (timestamp <= 0L) return false
        val delta = nowMs - timestamp
        if (delta in 0L until pastWindowMs) return true
        return delta < 0L && -delta < futureGraceMs
    }
}
