package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentReadEmptyPolicy
import com.maodouchat.domain.messaging.AgentTaskLinePolicy
import com.maodouchat.domain.messaging.AgentTaskReadLimitPolicy

// Agent 工具执行簇：待办任务只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskTodoReads {
    internal suspend fun listTasks(app: MaodouchatApp, limit: Int): String {
        val blocked = AgentMessagingReadGates.blockedChatIds(app)
        val tasks = app.database.aiTaskDao().listRecent(AgentTaskReadLimitPolicy.taskQueryLimit(limit))
            .filter { it.chatId.isBlank() || it.chatId !in blocked }
            .take(AgentTaskReadLimitPolicy.taskResultLimit(limit))
        if (tasks.isEmpty()) return AgentReadEmptyPolicy.noTasks()
        return tasks.joinToString("\n") { task ->
            AgentTaskLinePolicy.taskLine(task.id, task.chatId, task.title, task.isCompleted, task.dueText)
        }
    }
}
