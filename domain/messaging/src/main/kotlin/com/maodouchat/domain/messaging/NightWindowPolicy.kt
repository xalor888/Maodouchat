package com.maodouchat.domain.messaging

// 夜间窗口：当前分钟数是否落在窗口内（支持跨午夜窗口，如 21:00→07:00）；
// 分钟数 → "21:00" 式小时文本（原 SettingsThemeRows.formatMinutes 逐字平移）。
object NightWindowPolicy {
    fun isWithinNightWindow(currentMinute: Int, start: Int, end: Int): Boolean =
        if (start <= end) currentMinute in start until end
        else currentMinute >= start || currentMinute < end

    fun formatNightWindowHour(minutes: Int): String = String.format("%02d:00", minutes / 60)
}
