package com.maodouchat.domain.messaging

// 周报时间窗口：本周一 00:00（本地时区）到下周一 00:00。
object AiWeeklyReportWeekRange {
    private const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L

    /** 本周起点（周一 00:00）与终点（下周一 00:00），按本地时区。 */
    fun currentWeekRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        // 不用 set(DAY_OF_WEEK, MONDAY)：它的跨周方向取决于默认 locale 的
        // firstDayOfWeek（en_US 为周日），同一时刻会算出相差一周的两个窗口。
        // 这里直接按「距上一个周一几天」回退，语义与 locale 无关。
        val daysSinceMonday = (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysSinceMonday)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        return start to start + WEEK_MILLIS
    }
}
