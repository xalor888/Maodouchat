package com.maodouchat.ai

// 周报时间窗口：本周一 00:00（本地时区）到下周一 00:00。
internal object AiWeeklyReportWeekRange {
    private const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L

    /** 本周起点（周一 00:00）与终点（下周一 00:00），按本地时区。 */
    fun currentWeekRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        return start to start + WEEK_MILLIS
    }
}
