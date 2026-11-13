package com.maodouchat.domain.messaging

// 免打扰时段展示：分钟数 → "HH:mm"，越界钳制到一天内（0~1439）。
fun formatDndTime(minuteOfDay: Int): String {
    val safe = minuteOfDay.coerceIn(0, 1439)
    val h = safe / 60
    val m = safe % 60
    return "%02d:%02d".format(h, m)
}
