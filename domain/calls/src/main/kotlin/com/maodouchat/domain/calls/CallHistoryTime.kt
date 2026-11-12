package com.maodouchat.domain.calls

// 通话记录相对时间的分桶结果：文案由 app 侧按桶映射到 R.string / plurals。
sealed interface CallRelativeTime {
    data object JustNow : CallRelativeTime
    data class MinutesAgo(val minutes: Int) : CallRelativeTime
    data class HoursAgo(val hours: Int) : CallRelativeTime
    data class DaysAgo(val days: Int) : CallRelativeTime
    data class Date(val millis: Long) : CallRelativeTime
}

object CallHistoryTime {
    fun relativeTime(nowMillis: Long, millis: Long): CallRelativeTime {
        val diff = nowMillis - millis
        return when {
            diff < 60_000L -> CallRelativeTime.JustNow
            diff < 3_600_000L -> CallRelativeTime.MinutesAgo((diff / 60_000L).toInt())
            diff < 86_400_000L -> CallRelativeTime.HoursAgo((diff / 3_600_000L).toInt())
            diff < 7L * 86_400_000L -> CallRelativeTime.DaysAgo((diff / 86_400_000L).toInt())
            else -> CallRelativeTime.Date(millis)
        }
    }

    // 通话时长 "m:ss"；未接通/无效时长返回空串。
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val seconds = durationMs / 1000L
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
