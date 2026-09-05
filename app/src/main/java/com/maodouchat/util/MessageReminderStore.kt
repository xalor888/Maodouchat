package com.maodouchat.util

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel

/**
 * 消息「稍后提醒」（Remind Me Later）本地存储：按账号隔离，无需服务端参与。
 * 现已迁移至 Room 数据库 (message_reminders 表) 持久化。
 *
 * 触发：长按消息 → 选提醒时间 → WorkManager 到点发通知（点击直达聊天并高亮原消息）。
 * 时间窗口：1 分钟 ~ 30 天。
 */
object MessageReminderStore {

    data class MessageReminder(
        val id: String,
        val chatId: String,
        val messageId: String,
        val messagePreview: String,
        val remindAtMillis: Long,
        val createdAtMillis: Long,
        val ownerUserId: String
    )

    @Synchronized
    fun list(context: Context, ownerUserId: String): List<MessageReminder> {
        if (ownerUserId.isBlank()) return emptyList()
        return db(context).messageReminderDao().listForUserBlocking(ownerUserId).map { it.toModel() }
    }

    @Synchronized
    fun get(context: Context, id: String, ownerUserId: String): MessageReminder? {
        if (ownerUserId.isBlank() || id.isBlank()) return null
        return db(context).messageReminderDao().getByIdBlocking(id, ownerUserId)?.toModel()
    }

    @Synchronized
    fun upsert(context: Context, reminder: MessageReminder) {
        val owner = reminder.ownerUserId
        if (owner.isBlank() || reminder.id.isBlank()) return
        db(context).messageReminderDao().upsertBlocking(reminder.toEntity())
    }

    @Synchronized
    fun remove(context: Context, id: String, ownerUserId: String) {
        if (ownerUserId.isBlank() || id.isBlank()) return
        db(context).messageReminderDao().deleteByIdBlocking(id, ownerUserId)
    }

    /** 1.32：清除某会话的全部提醒（同时取消对应 Worker 由调用方负责）。 */
    @Synchronized
    fun clearForChat(context: Context, chatId: String, ownerUserId: String) {
        if (chatId.isBlank() || ownerUserId.isBlank()) return
        db(context).messageReminderDao().deleteForChatBlocking(ownerUserId, chatId)
    }

    private fun db(ctx: Context): AppDatabase =
        AppDatabase.getInstance(ctx.applicationContext)
}
