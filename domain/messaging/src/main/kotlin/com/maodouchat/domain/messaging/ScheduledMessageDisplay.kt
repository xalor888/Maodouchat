package com.maodouchat.domain.messaging

// 定时消息重复规则与禁言剩余时长的纯展示决策：文案由 app 侧按结果映射到 R.string / plurals。
sealed interface ScheduleRepeatKind {
    data object Weekdays : ScheduleRepeatKind
    data object Daily : ScheduleRepeatKind
    data object Weekly : ScheduleRepeatKind
    data object Custom : ScheduleRepeatKind
}

sealed interface MuteRemaining {
    data object JustNow : MuteRemaining
    data class Minutes(val minutes: Long) : MuteRemaining
    data class Hours(val hours: Long) : MuteRemaining
    data class Days(val days: Long) : MuteRemaining
}

object ScheduledMessageDisplay {

    // 重复规则分桶：工作日优先于间隔；非正间隔且非工作日 = 一次性，无文案。
    fun repeatKind(intervalMs: Long, weekdaysOnly: Boolean): ScheduleRepeatKind? =
        when {
            weekdaysOnly -> ScheduleRepeatKind.Weekdays
            intervalMs == 24L * 3600_000L -> ScheduleRepeatKind.Daily
            intervalMs == 7L * 24 * 3600_000L -> ScheduleRepeatKind.Weekly
            intervalMs <= 0L -> null
            else -> ScheduleRepeatKind.Custom
        }

    // repeatCount > 0 时返回剩余次数（已超发钳制为 0），否则不显示剩余次数。
    fun remainingRepeatCount(repeatCount: Int, occurrencesSent: Int): Int? =
        if (repeatCount > 0) (repeatCount - occurrencesSent).coerceAtLeast(0) else null

    // 禁言剩余时长分桶：分钟/小时/天，整数除法取整。
    fun muteRemaining(remainingMs: Long): MuteRemaining {
        val minutes = remainingMs / 60_000L
        return when {
            minutes <= 0L -> MuteRemaining.JustNow
            minutes < 60L -> MuteRemaining.Minutes(minutes)
            minutes < 24 * 60L -> MuteRemaining.Hours(minutes / 60L)
            else -> MuteRemaining.Days(minutes / (24 * 60L))
        }
    }
}
