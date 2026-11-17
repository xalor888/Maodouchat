package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentToolLimitPolicy
import com.maodouchat.domain.messaging.AgentToolNamePolicy
import com.maodouchat.domain.messaging.AgentToolPreview

// Agent 工具分发：任务簇（草稿/待办/通话/通知）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentTaskToolHost {
    internal suspend fun execute(name: String, app: MaodouchatApp, userId: String, args: Map<String, String>): String? = when (name) {
        AgentToolNamePolicy.LIST_DRAFTS -> AgentTaskDraftReads.listDrafts(app, userId)
        AgentToolNamePolicy.GET_DRAFT -> AgentTaskDraftReads.getDraft(app, userId, args["chatId"].orEmpty())
        AgentToolNamePolicy.LIST_LOCAL_TASKS -> AgentTaskTodoReads.listTasks(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_LOCAL_TASKS))
        AgentToolNamePolicy.LIST_MISSED_CALLS -> AgentTaskCallReads.listMissedCalls(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_MISSED_CALLS))
        AgentToolNamePolicy.LIST_NOTIFICATIONS -> AgentTaskNotificationReads.listNotifications(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_NOTIFICATIONS))
        AgentToolNamePolicy.CREATE_LOCAL_TASK -> AgentTaskWriteTools.createTask(app, args["chatId"].orEmpty(), args["title"].orEmpty(), args["dueText"])
        AgentToolNamePolicy.COMPLETE_LOCAL_TASK -> AgentTaskWriteTools.completeTask(app, args["taskId"].orEmpty(), AgentToolHost.parseBool(args["completed"]) == true)
        AgentToolNamePolicy.DELETE_LOCAL_TASK -> AgentTaskWriteTools.deleteTask(app, args["taskId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? =
        AgentToolPreview.preview(name, args)
}
