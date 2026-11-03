package com.maodouchat.core.model

// 任务提醒 WorkManager 命名簇：unique work 名、tag、input key 与任务 work 名构造。
object AiTaskReminderWorkNaming {
    const val UNIQUE_PERIODIC_WORK = "ai_task_reminder_reconcile_periodic"
    const val UNIQUE_RECONCILE_WORK = "ai_task_reminder_reconcile_now"
    const val TASK_WORK_PREFIX = "ai_task_reminder_"
    const val TAG_ALL = "ai_task_reminders"
    const val TAG_TASK = "ai_task_reminder_task"

    const val KEY_TASK_ID = "task_id"
    const val KEY_OWNER_USER_ID = "owner_user_id"
    const val KEY_FORCE_RECONCILE = "force_reconcile"
    const val KEY_NOTIFY_RETRY_COUNT = "notify_retry_count"

    fun taskWorkName(taskId: String): String = "$TASK_WORK_PREFIX$taskId"
}
