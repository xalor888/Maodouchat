package com.maodouchat.scheduling

import android.content.Context
import com.maodouchat.core.model.ConversationScheduleBackend
import com.maodouchat.core.model.ScheduledMessageRequest
import com.maodouchat.util.MessageReminderScheduler
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.domain.conversation.ScheduledMessageFactory
import com.maodouchat.util.ScheduledMessageScheduler
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.MessageReminderDao
import com.maodouchat.data.local.dao.ScheduledMessageDao
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import java.util.UUID

class AndroidConversationScheduleBackend(
    context: Context,
    private val scheduledMessageDao: ScheduledMessageDao = (context.applicationContext as? MaodouchatApp)?.database?.scheduledMessageDao()
        ?: AppDatabase.getInstance(context.applicationContext).scheduledMessageDao(),
    private val messageReminderDao: MessageReminderDao = (context.applicationContext as? MaodouchatApp)?.database?.messageReminderDao()
        ?: AppDatabase.getInstance(context.applicationContext).messageReminderDao(),
) : ConversationScheduleBackend {
    private val appContext = context.applicationContext

    // 该 backend 是唯一 DB 汇聚点，调用方（UI 回调 / loadChat）常在主线程。
    // Room 禁止主线程阻塞查询（曾导致打开聊天页直接 crash），这里统一把
    // 阻塞 DAO 调度到 IO 线程执行，调用方签名保持同步不变。
    private fun <T> onDb(block: () -> T): T = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
    }

    override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> =
        onDb { scheduledMessageDao.listForUserBlocking(ownerUserId.trim()).map { it.toModel() } }

    override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> =
        onDb { scheduledMessageDao.listForChatBlocking(ownerUserId.trim(), chatId.trim()).map { it.toModel() } }

    override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? =
        onDb { scheduledMessageDao.getByIdBlocking(id.trim(), ownerUserId.trim())?.toModel() }

    override fun addScheduled(
        ownerUserId: String,
        request: ScheduledMessageRequest,
    ): ScheduledMessage? {
        val item = ScheduledMessageFactory.build(
            request = request,
            ownerUserId = ownerUserId,
            pendingCount = {
                onDb { scheduledMessageDao.listForChatBlocking(ownerUserId.trim(), request.chatId) }.size
            },
            nowMs = System.currentTimeMillis(),
            idSuffix = UUID.randomUUID().toString().take(12),
            timeZoneId = java.time.ZoneId.systemDefault().id,
            idempotencyKey = UUID.randomUUID().toString(),
        ) ?: return null
        scheduledMessageDao.upsertBlocking(item.toEntity())
        return item
    }

    override fun updateScheduled(
        ownerUserId: String,
        id: String,
        text: String?,
        sendAtMillis: Long?,
    ): ScheduledMessage? {
        val userId = ownerUserId.trim()
        if (userId.isBlank() || id.isBlank()) return null
        val existing = onDb { scheduledMessageDao.getByIdBlocking(id, userId) }?.toModel() ?: return null
        val updated = ScheduledMessageFactory.applyUpdate(
            existing = existing,
            text = text,
            sendAtMillis = sendAtMillis,
            nowMs = System.currentTimeMillis(),
            timeZoneId = java.time.ZoneId.systemDefault().id,
        ) ?: return null
        onDb {
            scheduledMessageDao.updateTextAndTimeBlocking(
                id = id,
                ownerUserId = userId,
                text = updated.text,
                sendAtMillis = updated.sendAtMillis,
                timeZoneId = updated.timeZoneId,
            )
        }
        return updated
    }

    override fun removeScheduled(ownerUserId: String, id: String): Boolean =
        onDb { scheduledMessageDao.deleteByIdBlocking(id.trim(), ownerUserId.trim()) > 0 }

    override fun clearScheduled(ownerUserId: String, chatId: String): List<String> {
        val userId = ownerUserId.trim()
        val cId = chatId.trim()
        if (userId.isBlank() || cId.isBlank()) return emptyList()
        return onDb {
            val items = scheduledMessageDao.listForChatBlocking(userId, cId)
            val ids = items.map { it.id }
            scheduledMessageDao.deleteForChatBlocking(userId, cId)
            ids
        }
    }

    override fun scheduleJob(item: ScheduledMessage) =
        ScheduledMessageScheduler.schedule(appContext, item)

    override fun cancelJob(id: String) = ScheduledMessageScheduler.cancel(appContext, id)

    override fun rescheduleJob(item: ScheduledMessage) =
        ScheduledMessageScheduler.reschedule(appContext, item)

    override fun listReminders(ownerUserId: String): List<MessageReminder> =
        onDb { messageReminderDao.listForUserBlocking(ownerUserId.trim()).map { it.toModel() } }

    override fun upsertReminder(reminder: MessageReminder) {
        val owner = reminder.ownerUserId.trim()
        if (owner.isBlank() || reminder.id.isBlank()) return
        onDb { messageReminderDao.upsertBlocking(reminder.copy(ownerUserId = owner).toEntity()) }
    }

    override fun removeReminder(ownerUserId: String, id: String) {
        if (ownerUserId.isBlank() || id.isBlank()) return
        onDb { messageReminderDao.deleteByIdBlocking(id.trim(), ownerUserId.trim()) }
    }

    override fun clearReminders(ownerUserId: String, chatId: String) {
        if (ownerUserId.isBlank() || chatId.isBlank()) return
        onDb { messageReminderDao.deleteForChatBlocking(ownerUserId.trim(), chatId.trim()) }
    }

    override fun scheduleReminderJob(reminder: MessageReminder) =
        MessageReminderScheduler.schedule(appContext, reminder)

    override fun cancelReminderJob(id: String) = MessageReminderScheduler.cancel(appContext, id)
}
