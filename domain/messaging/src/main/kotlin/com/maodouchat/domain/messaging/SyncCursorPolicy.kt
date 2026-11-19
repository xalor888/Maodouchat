package com.maodouchat.domain.messaging

// 同步游标严格先后比较：先比时间戳，相等再比消息 id 字典序。
object SyncCursorPolicy {

    fun isStrictlyAfter(a: SyncCursor, b: SyncCursor): Boolean {
        if (a.timestampMs > b.timestampMs) return true
        if (a.timestampMs < b.timestampMs) return false
        return a.messageId > b.messageId
    }
}
