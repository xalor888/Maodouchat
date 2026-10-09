package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp

// Agent 工具执行簇：待办任务只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskTodoReads {
    internal suspend fun listTasks(app: MaodouchatApp, limit: Int): String {
        val blocked = AgentMessagingReadGates.blockedChatIds(app)
        val tasks = app.database.aiTaskDao().listRecent(limit.coerceIn(1, 80))
            .filter { it.chatId.isBlank() || it.chatId !in blocked }
            .take(limit.coerceIn(1, 40))
        if (tasks.isEmpty()) return "No tasks."
        return tasks.joinToString("\n") { task ->
            "${task.id}\t${task.chatId}\t${task.title}\tcompleted=${task.isCompleted}\tdue=${task.dueText.orEmpty()}"
        }
    }
}
