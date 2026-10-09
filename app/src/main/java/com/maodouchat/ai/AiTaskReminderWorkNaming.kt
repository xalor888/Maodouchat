package com.maodouchat.ai

// 任务提醒 WorkManager 命名簇：unique work 名、tag、input key 与任务 work 名构造。
internal object AiTaskReminderWorkNaming {
    internal const val UNIQUE_PERIODIC_WORK = "ai_task_reminder_reconcile_periodic"
    internal const val UNIQUE_RECONCILE_WORK = "ai_task_reminder_reconcile_now"
    internal const val TASK_WORK_PREFIX = "ai_task_reminder_"
    internal const val TAG_ALL = "ai_task_reminders"
    internal const val TAG_TASK = "ai_task_reminder_task"

    internal const val KEY_TASK_ID = "task_id"
    internal const val KEY_OWNER_USER_ID = "owner_user_id"
    internal const val KEY_FORCE_RECONCILE = "force_reconcile"
    internal const val KEY_NOTIFY_RETRY_COUNT = "notify_retry_count"

    internal fun taskWorkName(taskId: String): String = "$TASK_WORK_PREFIX$taskId"
}
