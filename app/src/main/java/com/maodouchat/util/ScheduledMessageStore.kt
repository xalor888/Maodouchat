package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager
import org.json.JSONArray
import org.json.JSONObject
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
    val weekdaysOnly: Boolean = false
)

/**
 * 账号隔离的定时消息本地队列（SharedPreferences JSON）。
 * 不进服务端明文；发出前可改可删。
 */
object ScheduledMessageStore {
    private const val PREFS = "scheduled_messages"
    private const val KEY_ITEMS = "items"

    @Synchronized
    fun list(context: Context): List<ScheduledMessage> {
        val userId = userId(context)
        if (userId.isBlank()) return emptyList()
        return readAll(context).filter { it.ownerUserId == userId }.map { it.item }
    }

    @Synchronized
    fun listForChat(context: Context, chatId: String): List<ScheduledMessage> =
        list(context).filter { it.chatId == chatId }.sortedBy { it.sendAtMillis }

    @Synchronized
    fun get(context: Context, id: String): ScheduledMessage? =
        list(context).firstOrNull { it.id == id }

    @Synchronized
    fun getForUser(context: Context, id: String, ownerUserId: String): ScheduledMessage? {
        if (ownerUserId.isBlank()) return null
        return readAll(context).firstOrNull {
            it.ownerUserId == ownerUserId && it.item.id == id
        }?.item
    }

    @Synchronized
    fun ownerOf(context: Context, id: String): String? =
        readAll(context).firstOrNull { it.item.id == id }?.ownerUserId

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
    ): ScheduledMessage? {
        val userId = userId(context)
        if (userId.isBlank()) return null
        val normalized = ScheduledMessagePolicy.normalizeText(text)
        if (!ScheduledMessagePolicy.isValidText(normalized)) return null
        val now = System.currentTimeMillis()
        val sendAt = ScheduledMessagePolicy.clampSendAt(sendAtMillis, now)
        val pending = listForChat(context, chatId)
        if (!ScheduledMessagePolicy.canAddMore(pending.size)) return null
        val item = ScheduledMessage(
            id = "sch_${UUID.randomUUID().toString().take(12)}",
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
            weekdaysOnly = weekdaysOnly
        )
        val all = readAll(context).toMutableList()
        all.add(Owned(userId, item))
        writeAll(context, all)
        return item
    }

    @Synchronized
    fun updateTextAndTime(
        context: Context,
        id: String,
        text: String? = null,
        sendAtMillis: Long? = null
    ): ScheduledMessage? {
        val userId = userId(context)
        if (userId.isBlank()) return null
        val all = readAll(context).toMutableList()
        val idx = all.indexOfFirst { it.ownerUserId == userId && it.item.id == id }
        if (idx < 0) return null
        val current = all[idx].item
        val now = System.currentTimeMillis()
        val nextText = text?.let { ScheduledMessagePolicy.normalizeText(it) } ?: current.text
        if (!ScheduledMessagePolicy.isValidText(nextText)) return null
        val nextSendAt = sendAtMillis?.let { ScheduledMessagePolicy.clampSendAt(it, now) } ?: current.sendAtMillis
        val updated = current.copy(text = nextText, sendAtMillis = nextSendAt)
        all[idx] = Owned(userId, updated)
        writeAll(context, all)
        return updated
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val userId = userId(context)
        return removeForUser(context, id, userId)
    }

    @Synchronized
    fun removeForUser(context: Context, id: String, ownerUserId: String): Boolean {
        if (ownerUserId.isBlank()) return false
        val all = readAll(context)
        val next = all.filterNot { it.ownerUserId == ownerUserId && it.item.id == id }
        if (next.size == all.size) return false
        writeAll(context, next)
        return true
    }

    @Synchronized
    fun due(context: Context, nowMillis: Long = System.currentTimeMillis()): List<ScheduledMessage> =
        list(context).filter { it.sendAtMillis <= nowMillis }

    @Synchronized
    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        writeAll(context, readAll(context).filterNot { it.ownerUserId == userId })
    }

    /** Cancel-store rows for one chat (current owner). Returns removed item ids. */
    @Synchronized
    fun clearForChat(context: Context, chatId: String): List<String> {
        if (chatId.isBlank()) return emptyList()
        val userId = userId(context)
        if (userId.isBlank()) return emptyList()
        val all = readAll(context)
        val removedIds = all
            .filter { it.ownerUserId == userId && it.item.chatId == chatId }
            .map { it.item.id }
        if (removedIds.isEmpty()) return emptyList()
        writeAll(context, all.filterNot { it.ownerUserId == userId && it.item.chatId == chatId })
        return removedIds
    }

    private data class Owned(val ownerUserId: String, val item: ScheduledMessage)

    private fun userId(ctx: Context): String =
        TokenManager.getInstance(ctx).getUserId().orEmpty()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(context: Context): List<Owned> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val owner = o.optString("ownerUserId")
                    val id = o.optString("id")
                    val chatId = o.optString("chatId")
                    val text = o.optString("text")
                    if (owner.isBlank() || id.isBlank() || chatId.isBlank() || text.isBlank()) continue
                    add(
                        Owned(
                            owner,
                            ScheduledMessage(
                                id = id,
                                chatId = chatId,
                                peerUserId = o.optString("peerUserId"),
                                text = text,
                                sendAtMillis = o.optLong("sendAtMillis"),
                                createdAtMillis = o.optLong("createdAtMillis"),
                                isGroup = o.optBoolean("isGroup", false),
                                ownerUserId = owner,
                                repeatIntervalMs = o.optLong("repeatIntervalMs", 0L),
                                repeatCount = o.optInt("repeatCount", 0),
                                occurrencesSent = o.optInt("occurrencesSent", 0),
                                weekdaysOnly = o.optBoolean("weekdaysOnly", false)
                            )
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, items: List<Owned>) {
        val arr = JSONArray()
        items.forEach { owned ->
            arr.put(
                JSONObject()
                    .put("ownerUserId", owned.ownerUserId)
                    .put("id", owned.item.id)
                    .put("chatId", owned.item.chatId)
                    .put("peerUserId", owned.item.peerUserId)
                    .put("text", owned.item.text)
                    .put("sendAtMillis", owned.item.sendAtMillis)
                    .put("createdAtMillis", owned.item.createdAtMillis)
                    .put("isGroup", owned.item.isGroup)
                    .put("repeatIntervalMs", owned.item.repeatIntervalMs)
                    .put("repeatCount", owned.item.repeatCount)
                    .put("occurrencesSent", owned.item.occurrencesSent)
                    .put("weekdaysOnly", owned.item.weekdaysOnly)
            )
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
