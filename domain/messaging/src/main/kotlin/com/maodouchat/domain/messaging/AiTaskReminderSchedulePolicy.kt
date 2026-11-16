package com.maodouchat.domain.messaging

// AI 任务提醒排队资格：未完成、未提醒过、有到期时间且提醒开关开着才排队；
// 开关读取（AiTaskReminderPreferences）与 owner 校验仍在 app 侧。
object AiTaskReminderSchedulePolicy {

    fun shouldSchedule(
        isCompleted: Boolean,
        remindedAt: Long?,
        dueAt: Long?,
        remindersAllowed: Boolean
    ): Boolean = !isCompleted && remindedAt == null && dueAt != null && remindersAllowed
}
