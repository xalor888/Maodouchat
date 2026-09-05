package com.maodouchat.util

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.network.TokenManager
import java.util.TimeZone
import java.util.UUID

data class ScheduledMessage(
    val id: String,
    val chatId: String,
    val peerUserId: String,
    val text: String,
    val sendAtMillis: Long,
    val createdAtMillis: Long,
    val isGroup: Boolean = false,
    val ownerUserId: String = "",
    /** 1.07：重复发送间隔毫秒（0=一次性；如每日/每周提醒）。 */
    val repeatIntervalMs: Long = 0L,
    /** 1.21：重复总次数（0=不限，按已发次数 occurrencesSent 推进，达上限停止重排）。 */
    val repeatCount: Int = 0,
    /** 1.21：本链已发送次数（每次重排 +1，用于判定是否继续）。 */
    val occurrencesSent: Int = 0,
    /** 1.62：仅工作日重复（周一至周五，跳过周末）。 */
    val weekdaysOnly: Boolean = false,
    val status: String = "PENDING",
    val attempt: Int = 0,
    val timeZoneId: String = "",
    val idempotencyKey: String = "",
)

/**
 * 账号隔离的定时消息本地队列（Room 数据库持久化）。
 * 不进服务端明文；发出前可改可删。
 */
object ScheduledMessageStore {

    @Synchronized
    fun list(context: Context): List<ScheduledMessage> {
        val userId = userId(context)
        return listForUser(context, userId)
    }

    @Synchronized
    fun listForUser(context: Context, ownerUserId: String): List<ScheduledMessage> {
        if (ownerUserId.isBlank()) return emptyList()
        return db(context).scheduledMessageDao().listForUserBlocking(ownerUserId).map { it.toModel() }
    }

    @Synchronized
    fun listForChat(context: Context, chatId: String): List<ScheduledMessage> =
        listForChatForUser(context, chatId, userId(context))

    @Synchronized
    fun listForChatForUser(
        context: Context,
        chatId: String,
        ownerUserId: String,
    ): List<ScheduledMessage> {
        if (ownerUserId.isBlank() || chatId.isBlank()) return emptyList()
        return db(context).scheduledMessageDao().listForChatBlocking(ownerUserId, chatId).map { it.toModel() }
    }

    @Synchronized
    fun get(context: Context, id: String): ScheduledMessage? =
        db(context).scheduledMessageDao().getByIdWithoutOwnerBlocking(id)?.toModel()

    @Synchronized
    fun getForUser(context: Context, id: String, ownerUserId: String): ScheduledMessage? {
        if (ownerUserId.isBlank() || id.isBlank()) return null
        return db(context).scheduledMessageDao().getByIdBlocking(id, ownerUserId)?.toModel()
    }

    @Synchronized
    fun ownerOf(context: Context, id: String): String? =
        db(context).scheduledMessageDao().getByIdWithoutOwnerBlocking(id)?.ownerUserId

    @Synchronized
    fun add(
        context: Context,
        chatId: String,
        peerUserId: String,
        text: String,
        sendAtMillis: Long,
        isGroup: Boolean = false,
        repeatIntervalMs: Long = 0L,
        repeatCount: Int = 0,
        occurrencesSent: Int = 0,
        weekdaysOnly: Boolean = false
    ): ScheduledMessage? = addForUser(
        context = context,
        ownerUserId = userId(context),
        chatId = chatId,
        peerUserId = peerUserId,
        text = text,
        sendAtMillis = sendAtMillis,
        isGroup = isGroup,
        repeatIntervalMs = repeatIntervalMs,
        repeatCount = repeatCount,
        occurrencesSent = occurrencesSent,
        weekdaysOnly = weekdaysOnly,
    )

    @Synchronized
    fun addForUser(
        context: Context,
        ownerUserId: String,
        chatId: String,
        peerUserId: String,
        text: String,
        sendAtMillis: Long,
        isGroup: Boolean = false,
        repeatIntervalMs: Long = 0L,
        repeatCount: Int = 0,
        occurrencesSent: Int = 0,
        weekdaysOnly: Boolean = false,
    ): ScheduledMessage? {
        val userId = ownerUserId.trim()
        if (userId.isBlank() || chatId.isBlank()) return null
        val normalized = ScheduledMessagePolicy.normalizeText(text)
        if (!ScheduledMessagePolicy.isValidText(normalized)) return null
        val now = System.currentTimeMillis()
        val sendAt = ScheduledMessagePolicy.clampSendAt(sendAtMillis, now)
        val pending = listForChatForUser(context, chatId, userId)
        if (!ScheduledMessagePolicy.canAddMore(pending.size)) return null
        val randomSuffix = UUID.randomUUID().toString().take(12)
        val item = ScheduledMessage(
            id = "sch_$randomSuffix",
            chatId = chatId,
            peerUserId = peerUserId,
            text = normalized,
            sendAtMillis = sendAt,
            createdAtMillis = now,
            isGroup = isGroup,
            ownerUserId = userId,
            repeatIntervalMs = repeatIntervalMs.coerceAtLeast(0L),
            repeatCount = repeatCount.coerceAtLeast(0),
            occurrencesSent = occurrencesSent.coerceAtLeast(0),
            weekdaysOnly = weekdaysOnly,
            timeZoneId = TimeZone.getDefault().id,
            idempotencyKey = "sm_$randomSuffix"
        )
        db(context).scheduledMessageDao().upsertBlocking(item.toEntity())
        return item
    }

    @Synchronized
    fun updateTextAndTime(
        context: Context,
        id: String,
        text: String? = null,
        sendAtMillis: Long? = null
    ): ScheduledMessage? = updateTextAndTimeForUser(
        context = context,
        ownerUserId = userId(context),
        id = id,
        text = text,
        sendAtMillis = sendAtMillis,
    )

    @Synchronized
    fun updateTextAndTimeForUser(
        context: Context,
        ownerUserId: String,
        id: String,
        text: String? = null,
        sendAtMillis: Long? = null,
    ): ScheduledMessage? {
        val userId = ownerUserId.trim()
        if (userId.isBlank() || id.isBlank()) return null
        val current = db(context).scheduledMessageDao().getByIdBlocking(id, userId)?.toModel() ?: return null
        val now = System.currentTimeMillis()
        val nextText = text?.let { ScheduledMessagePolicy.normalizeText(it) } ?: current.text
        if (!ScheduledMessagePolicy.isValidText(nextText)) return null
        val nextSendAt = sendAtMillis?.let { ScheduledMessagePolicy.clampSendAt(it, now) } ?: current.sendAtMillis
        val updated = current.copy(text = nextText, sendAtMillis = nextSendAt)
        db(context).scheduledMessageDao().upsertBlocking(updated.toEntity())
        return updated
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val userId = userId(context)
        return removeForUser(context, id, userId)
    }

    @Synchronized
    fun removeForUser(context: Context, id: String, ownerUserId: String): Boolean {
        if (ownerUserId.isBlank() || id.isBlank()) return false
        return db(context).scheduledMessageDao().deleteByIdBlocking(id, ownerUserId) > 0
    }

    @Synchronized
    fun due(context: Context, nowMillis: Long = System.currentTimeMillis()): List<ScheduledMessage> {
        val uid = userId(context)
        if (uid.isBlank()) return emptyList()
        return db(context).scheduledMessageDao().listForUserBlocking(uid)
            .filter { it.sendAtMillis <= nowMillis }
            .map { it.toModel() }
    }

    /** Cancel-store rows for one chat (current owner). Returns removed item ids. */
    @Synchronized
    fun clearForChat(context: Context, chatId: String): List<String> {
        return clearForChatForUser(context, chatId, userId(context))
    }

    @Synchronized
    fun clearForChatForUser(
        context: Context,
        chatId: String,
        ownerUserId: String,
    ): List<String> {
        if (chatId.isBlank() || ownerUserId.isBlank()) return emptyList()
        val items = db(context).scheduledMessageDao().listForChatBlocking(ownerUserId, chatId)
        if (items.isEmpty()) return emptyList()
        val removedIds = items.map { it.id }
        db(context).scheduledMessageDao().deleteForChatBlocking(ownerUserId, chatId)
        return removedIds
    }

    private fun db(ctx: Context): AppDatabase =
        AppDatabase.getInstance(ctx.applicationContext)

    private fun userId(ctx: Context): String =
        TokenManager.getInstance(ctx.applicationContext).getUserId().orEmpty()
}
