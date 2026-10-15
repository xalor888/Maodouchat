package com.maodouchat.scheduling

import android.content.Context
import com.maodouchat.util.MessageReminderScheduler
import com.maodouchat.util.MessageReminder
import com.maodouchat.util.ScheduledMessage
import com.maodouchat.util.ScheduledMessagePolicy
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
        val userId = ownerUserId.trim()
        if (userId.isBlank() || request.chatId.isBlank()) return null
        val normalized = ScheduledMessagePolicy.normalizeText(request.text)
        if (!ScheduledMessagePolicy.isValidText(normalized)) return null
        val now = System.currentTimeMillis()
        val sendAt = ScheduledMessagePolicy.clampSendAt(request.sendAtMillis, now)
        val pending = onDb { scheduledMessageDao.listForChatBlocking(userId, request.chatId) }
        if (!ScheduledMessagePolicy.canAddMore(pending.size)) return null
        val item = ScheduledMessage(
            id = "sch_${UUID.randomUUID().toString().take(12)}",
            chatId = request.chatId,
            peerUserId = request.peerUserId,
            text = normalized,
            sendAtMillis = sendAt,
            createdAtMillis = now,
            isGroup = request.isGroup,
            ownerUserId = userId,
            repeatIntervalMs = request.repeatIntervalMs.coerceAtLeast(0L),
            repeatCount = request.repeatCount.coerceAtLeast(0),
            occurrencesSent = 0,
            weekdaysOnly = request.weekdaysOnly,
            status = "PENDING",
            attempt = 0,
            timeZoneId = java.time.ZoneId.systemDefault().id,
            idempotencyKey = UUID.randomUUID().toString(),
        )
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
        val existing = onDb { scheduledMessageDao.getByIdBlocking(id, userId) } ?: return null
        val nextText = text?.let { ScheduledMessagePolicy.normalizeText(it) } ?: existing.text
        if (!ScheduledMessagePolicy.isValidText(nextText)) return null
        val now = System.currentTimeMillis()
        val nextSendAt = sendAtMillis?.let { ScheduledMessagePolicy.clampSendAt(it, now) } ?: existing.sendAtMillis
        val timeZoneId = existing.timeZoneId.ifBlank { java.time.ZoneId.systemDefault().id }
        onDb {
            scheduledMessageDao.updateTextAndTimeBlocking(
                id = id,
                ownerUserId = userId,
                text = nextText,
                sendAtMillis = nextSendAt,
                timeZoneId = timeZoneId,
            )
        }
        return existing.copy(text = nextText, sendAtMillis = nextSendAt, timeZoneId = timeZoneId).toModel()
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
