package com.maodouchat.domain.messaging

// 勿扰窗口钳制：分钟 0~1439、小时 0~23（逐字平移自设置通知簇）。
object DndWindowPolicy {
    const val MINUTE_OF_DAY_MAX = 1439
    const val HOUR_OF_DAY_MAX = 23

    fun clampMinuteOfDay(minute: Int): Int = minute.coerceIn(0, MINUTE_OF_DAY_MAX)
    fun clampHourOfDay(hour: Int): Int = hour.coerceIn(0, HOUR_OF_DAY_MAX)
    fun clampWindow(startMinute: Int, endMinute: Int): Pair<Int, Int> =
        clampMinuteOfDay(startMinute) to clampMinuteOfDay(endMinute)
}
