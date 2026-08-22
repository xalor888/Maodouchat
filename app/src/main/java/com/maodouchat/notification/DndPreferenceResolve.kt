package com.maodouchat.notification

/**
 * 勿扰偏好的遗留推断：小时级窗口曾是唯一控制，显式 [dnd_enabled] / 分钟 key 后加。
 * 纯函数，供 [NotificationPreferences] 读取与迁移共用，避免「设了时段却仍响」。
 */
object DndPreferenceResolve {

    /**
     * @param enabledStored 显式开关；null 表示 key 不存在（不是 false）。
     * @param startHourPresent / endHourPresent 账号作用域下是否写过小时窗口。
     */
    fun enabled(
        enabledStored: Boolean?,
        startHourPresent: Boolean,
        endHourPresent: Boolean,
        startHour: Int,
        endHour: Int,
    ): Boolean {
        if (enabledStored != null) return enabledStored
        if (!startHourPresent && !endHourPresent) return false
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        // 旧版：start==end 关闭计划，否则窗口生效（与 LocalNotificationSuppressPolicy 一致）。
        return start != end
    }

    fun startMinute(storedMinute: Int?, startHour: Int): Int {
        if (storedMinute != null) return storedMinute.coerceIn(0, 1439)
        return startHour.coerceIn(0, 23) * 60
    }

    fun endMinute(storedMinute: Int?, endHour: Int): Int {
        if (storedMinute != null) return storedMinute.coerceIn(0, 1439)
        return endHour.coerceIn(0, 23) * 60
    }
}
