package com.maodouchat.domain.calls

// 通话记录过滤：仅看未接先筛 state，再按对端名/ID 搜。
// 自 CallHistoryScreen 的 filteredLogs 收敛。
object CallHistoryFilterPolicy {

    fun matches(entry: CallLogEntry, query: String): Boolean =
        entry.peerName.contains(query, ignoreCase = true) ||
            entry.peerId.contains(query, ignoreCase = true)

    fun filter(logs: List<CallLogEntry>, query: String, onlyMissed: Boolean): List<CallLogEntry> {
        val base = if (onlyMissed) logs.filter { it.state == State.MISSED } else logs
        val trimmed = query.trim()
        if (trimmed.isBlank()) return base
        return base.filter { matches(it, trimmed) }
    }
}
