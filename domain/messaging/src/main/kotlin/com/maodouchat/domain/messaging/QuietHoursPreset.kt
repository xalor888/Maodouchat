package com.maodouchat.domain.messaging

import com.maodouchat.core.model.QuietWindow

// 免打扰预置时段：起止分钟（自午夜起）；R.string 映射留在 app 侧。
enum class QuietHoursPreset(val startMinute: Int, val endMinute: Int) {
    Night(22 * 60, 7 * 60),
    Lunch(12 * 60, 14 * 60),
    Sleep(23 * 60, 8 * 60),
    Workday(9 * 60, 18 * 60);

    // 该预置与当前窗口是同一生效窗口。
    fun matches(window: QuietWindow): Boolean =
        window.enabled && window.startMinute == startMinute && window.endMinute == endMinute

    // 预置对应的生效窗口。
    fun toWindow(): QuietWindow =
        QuietWindow(enabled = true, startMinute = startMinute, endMinute = endMinute)
}
