package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentToolLimitPolicy
import com.maodouchat.domain.messaging.AgentToolPreview

// Agent 工具分发：任务簇（草稿/待办/通话/通知）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentTaskToolHost {
    internal suspend fun execute(name: String, app: MaodouchatApp, userId: String, args: Map<String, String>): String? = when (name) {
        "list_drafts" -> AgentTaskDraftReads.listDrafts(app, userId)
        "get_draft" -> AgentTaskDraftReads.getDraft(app, userId, args["chatId"].orEmpty())
        "list_local_tasks" -> AgentTaskTodoReads.listTasks(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit("list_local_tasks"))
        "list_missed_calls" -> AgentTaskCallReads.listMissedCalls(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit("list_missed_calls"))
        "list_notifications" -> AgentTaskNotificationReads.listNotifications(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit("list_notifications"))
        "create_local_task" -> AgentTaskWriteTools.createTask(app, args["chatId"].orEmpty(), args["title"].orEmpty(), args["dueText"])
        "complete_local_task" -> AgentTaskWriteTools.completeTask(app, args["taskId"].orEmpty(), AgentToolHost.parseBool(args["completed"]) == true)
        "delete_local_task" -> AgentTaskWriteTools.deleteTask(app, args["taskId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? =
        AgentToolPreview.preview(name, args)
}
