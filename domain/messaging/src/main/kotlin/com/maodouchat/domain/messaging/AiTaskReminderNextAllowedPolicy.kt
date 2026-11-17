package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiTaskDndPolicy
import java.util.Calendar

// 任务提醒勿扰“下一允许时刻”：把当前时刻换算成当日分钟与窗口结束时刻，再走 AiTaskDndPolicy 判定。
// 原在 app 侧 AiTaskReminderPrefsReads.nextAllowedTime，逐字迁入。
object AiTaskReminderNextAllowedPolicy {

    fun nextAllowedTime(
        nowMs: Long,
        dndEnabled: Boolean,
        startMinute: Int,
        endMinute: Int
    ): Long? {
        if (!dndEnabled) return null
        val current = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMinute = current.get(Calendar.HOUR_OF_DAY) * 60 + current.get(Calendar.MINUTE)
        // 窗口结束时刻按分钟精度算：非整点时刻（如 22:31）也不能漏抑制。
        val endOfWindow = (current.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, endMinute / 60)
            set(Calendar.MINUTE, endMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return AiTaskDndPolicy.nextAllowedTime(
            nowMs = nowMs,
            minuteOfDay = nowMinute,
            startMinute = startMinute,
            endMinute = endMinute,
            endOfWindowMs = endOfWindow
        )
    }
}
