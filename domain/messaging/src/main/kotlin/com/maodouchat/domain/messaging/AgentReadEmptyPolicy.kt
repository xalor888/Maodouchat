package com.maodouchat.domain.messaging

// Agent 只读查询空结果文案：未接来电、草稿、待办、通知均为空时返回的提示。
// 自 app 侧 AgentTaskCallReads / AgentTaskDraftReads / AgentTaskTodoReads / AgentTaskNotificationReads 收敛。
object AgentReadEmptyPolicy {

    fun noMissedCalls(): String = "No missed calls."

    fun noDrafts(): String = "No drafts."

    fun noDraft(): String = "No draft."

    fun noTasks(): String = "No tasks."

    fun noNotifications(): String = "No notifications."
}
