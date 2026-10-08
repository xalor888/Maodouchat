package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.AiTaskEntity
import java.util.UUID

// Agent 工具执行簇：任务写操作（创建/完成/删除）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致。
internal object AgentTaskWriteTools {
        internal suspend fun createTask(app: MaodouchatApp, chatId: String, title: String, dueText: String?): String {
            val cleanTitle = title.trim().take(300)
            if (chatId.isBlank() || cleanTitle.isBlank()) return "Error: chatId and title required"
            if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            val now = System.currentTimeMillis()
            val entity = AiTaskEntity(
                id = "task_${UUID.randomUUID()}",
                chatId = chatId,
                sourceQuery = "agent",
                title = cleanTitle,
                dueText = dueText?.trim()?.take(120)?.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now
            )
            app.database.aiTaskDao().upsertAll(listOf(entity))
            return "Created task ${entity.id}"
        }

        internal suspend fun completeTask(app: MaodouchatApp, taskId: String, completed: Boolean): String {
            if (taskId.isBlank()) return "Error: taskId required"
            if (app.database.aiTaskDao().getById(taskId) == null) return "Error: task not found"
            val now = System.currentTimeMillis()
            app.database.aiTaskDao().setCompleted(
                taskId = taskId,
                completed = completed,
                completedAt = if (completed) now else null,
                updatedAt = now
            )
            return "Task $taskId completed=$completed"
        }

        internal suspend fun deleteTask(app: MaodouchatApp, taskId: String): String {
            if (taskId.isBlank()) return "Error: taskId required"
            if (app.database.aiTaskDao().getById(taskId) == null) return "Error: task not found"
            app.database.aiTaskDao().delete(taskId)
            return "Deleted task $taskId"
        }
}
