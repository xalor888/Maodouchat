package com.maodouchat.data.repository

import android.content.Context
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.data.local.dao.AiTaskDao
import com.maodouchat.data.local.entity.AiTaskEntity
import kotlinx.coroutines.flow.Flow

class AiTaskRepository(
    private val dao: AiTaskDao,
    context: Context
) {
    private val appContext = context.applicationContext

    fun observeTasks(chatId: String): Flow<List<AiTaskEntity>> = dao.observeByChatId(chatId)

    suspend fun saveTasks(tasks: List<AiTaskEntity>) {
        if (tasks.isEmpty()) return
        dao.upsertAll(tasks)
        tasks.forEach { task -> AiTaskReminderScheduler.scheduleTask(appContext, task) }
    }

    suspend fun setCompleted(taskId: String, completed: Boolean) {
        val now = System.currentTimeMillis()
        dao.setCompleted(taskId, completed, now.takeIf { completed }, now)
        if (completed) {
            AiTaskReminderScheduler.cancelTask(appContext, taskId)
        } else {
            dao.getById(taskId)?.let { AiTaskReminderScheduler.scheduleTask(appContext, it) }
        }
    }

    suspend fun delete(taskId: String) {
        AiTaskReminderScheduler.cancelTask(appContext, taskId)
        dao.delete(taskId)
    }

    suspend fun deleteByChatId(chatId: String) {
        dao.getIdsByChatId(chatId).forEach { AiTaskReminderScheduler.cancelTask(appContext, it) }
        dao.deleteByChatId(chatId)
    }

    /**
     * 1.304：清除某会话全部已完成任务（取消其提醒调度 + 删除记录）。
     */
    suspend fun deleteCompletedByChatId(chatId: String) {
        dao.getIdsByChatId(chatId).forEach { id ->
            try {
                val entity = dao.getById(id)
                if (entity?.isCompleted == true) AiTaskReminderScheduler.cancelTask(appContext, id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        dao.deleteCompletedByChatId(chatId)
    }

    /** 删除完成时间早于 :before 的已完成任务（完成状态保留期清理）。 */
    suspend fun pruneCompletedOlderThan(before: Long) {
        dao.deleteCompletedOlderThan(before)
    }
}
