package com.maodouchat.domain.conversation

// 密聊会话 TTL 剩余秒数纯计算：由 app 侧 SecretSessionTtl 平移；
// Context 绑定（开关读取、销毁执行）仍在 app 侧，这里只留算术核。
object SecretSessionTtlPolicy {

    // 活动时间未知（<=0）时 fail-closed，按已过期处理。
    fun remainingSecondsFor(ttlSeconds: Long, lastActivityAt: Long, nowMs: Long): Long {
        if (lastActivityAt <= 0L) return 0L
        return (lastActivityAt + ttlSeconds * 1000L - nowMs) / 1000L
    }
}
