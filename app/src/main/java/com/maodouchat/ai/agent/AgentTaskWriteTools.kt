package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.domain.messaging.AgentEntityIdPolicy
import com.maodouchat.domain.messaging.AgentTaskResultPolicy
import com.maodouchat.domain.messaging.AgentToolHostPolicy
import com.maodouchat.domain.messaging.AgentToolInputPolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import java.util.UUID

// Agent 工具执行簇：任务写操作（创建/完成/删除）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致。
internal object AgentTaskWriteTools {
        internal suspend fun createTask(app: MaodouchatApp, chatId: String, title: String, dueText: String?): String {
            val cleanTitle = AgentToolInputPolicy.cleanTaskTitle(title)
            if (chatId.isBlank() || cleanTitle.isBlank()) return AgentWriteErrorPolicy.required("chatId", "title")
            if (app.database.chatDao().getChatById(chatId) == null) return AgentWriteErrorPolicy.chatNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            val now = System.currentTimeMillis()
            val entity = AiTaskEntity(
                id = AgentEntityIdPolicy.taskId(UUID.randomUUID().toString()),
                chatId = chatId,
                sourceQuery = AgentToolHostPolicy.AGENT_SOURCE,
                title = cleanTitle,
                dueText = AgentToolInputPolicy.cleanTaskDueText(dueText),
                createdAt = now,
                updatedAt = now
            )
            app.database.aiTaskDao().upsertAll(listOf(entity))
            return AgentTaskResultPolicy.created(entity.id)
        }

        internal suspend fun completeTask(app: MaodouchatApp, taskId: String, completed: Boolean): String {
            if (taskId.isBlank()) return AgentWriteErrorPolicy.required("taskId")
            if (app.database.aiTaskDao().getById(taskId) == null) return AgentWriteErrorPolicy.taskNotFound()
            val now = System.currentTimeMillis()
            app.database.aiTaskDao().setCompleted(
                taskId = taskId,
                completed = completed,
                completedAt = if (completed) now else null,
                updatedAt = now
            )
            return AgentTaskResultPolicy.completed(taskId, completed)
        }

        internal suspend fun deleteTask(app: MaodouchatApp, taskId: String): String {
            if (taskId.isBlank()) return AgentWriteErrorPolicy.required("taskId")
            if (app.database.aiTaskDao().getById(taskId) == null) return AgentWriteErrorPolicy.taskNotFound()
            app.database.aiTaskDao().delete(taskId)
            return AgentTaskResultPolicy.deleted(taskId)
        }
}
