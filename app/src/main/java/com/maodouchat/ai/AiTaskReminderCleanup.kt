package com.maodouchat.ai

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 打开 AI 任务页时取消该会话的全部提醒（U02 延伸：自 `ui/screen/chatdetail/AiTasksScreen`
 * 的 app 单例/DAO 直连收口）。
 *
 * 语义逐字对齐原实现（8.48 修复）：
 * - 后台 scope fire-and-forget（不阻塞任务页打开）；
 * - 先取该会话全部任务 id，再逐个 `AiTaskReminderScheduler.cancelTask`（托盘 + WorkManager 作业）；
 * - 失败只记日志（原实现同样吞掉），`CancellationException` 重抛。
 */
object AiTaskReminderCleanup {

    fun cancelForChatAsync(chatId: String) {
        if (chatId.isBlank()) return
        val app = MaodouchatApp.instance
        app.applicationScope.launch {
            try {
                app.database.aiTaskDao().getIdsByChatId(chatId).forEach { taskId ->
                    AiTaskReminderScheduler.cancelTask(app, taskId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("AiTaskReminderCleanup", "cancel reminders failed", error)
            }
        }
    }
}
