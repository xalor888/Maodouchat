package com.maodouchat.domain.messaging

// 静音至档位：小时数 → 截止时间；R.string 映射留在 app 侧。
enum class SilentUntilOption(val hours: Long) {
    Hour1(1),
    Hours8(8),
    Hours24(24)
}

object SilentUntilPolicy {

    // 原 ChatSilentUntilDialog 的截止时间计算逐字平移：当前毫秒 + 小时数 × 3600_000。
    fun deadlineMillis(nowMillis: Long, hours: Long): Long =
        nowMillis + hours * 3600_000L

    // 已有生效静音：截止时间晚于当前时间。
    fun isActive(silentUntilMillis: Long, nowMillis: Long): Boolean =
        silentUntilMillis > nowMillis
}
