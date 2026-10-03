package com.maodouchat.ai

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
