package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息「稍后提醒」（Remind Me Later）本地存储：按账号隔离，无需服务端参与。
 *
 * 触发：长按消息 → 选提醒时间 → WorkManager 到点发通知（点击直达聊天并高亮原消息）。
 * 时间窗口：1 分钟 ~ 30 天。每次提醒保留最近 200 条。
 */
object MessageReminderStore {

    private const val PREFS_NAME = "message_reminder_prefs"
    private const val KEY_REMINDERS = "reminders"
    private const val MAX_REMINDERS = 200

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
    fun list(context: Context, ownerUserId: String): List<MessageReminder> =
        decode(prefs(context).getString(key(ownerUserId), null))
            .sortedBy { it.remindAtMillis }

    @Synchronized
    fun get(context: Context, id: String, ownerUserId: String): MessageReminder? =
        list(context, ownerUserId).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(context: Context, reminder: MessageReminder) {
        val owner = reminder.ownerUserId
        if (owner.isBlank() || reminder.id.isBlank()) return
        val current = list(context, owner).filterNot { it.id == reminder.id }
        val next = (current + reminder)
            .sortedBy { it.remindAtMillis }
            .takeLast(MAX_REMINDERS)
        prefs(context).edit().putString(key(owner), encode(next)).apply()
    }

    @Synchronized
    fun remove(context: Context, id: String, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        val next = list(context, ownerUserId).filterNot { it.id == id }
        prefs(context).edit().putString(key(ownerUserId), encode(next)).apply()
    }

    /** 1.32：清除某会话的全部提醒（同时取消对应 Worker 由调用方负责）。 */
    @Synchronized
    fun clearForChat(context: Context, chatId: String, ownerUserId: String) {
        if (chatId.isBlank() || ownerUserId.isBlank()) return
        val next = list(context, ownerUserId).filterNot { it.chatId == chatId }
        prefs(context).edit().putString(key(ownerUserId), encode(next)).apply()
    }

    @Synchronized
    fun clearForUser(context: Context, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        prefs(context).edit().remove(key(ownerUserId)).apply()
    }

    private fun key(ownerUserId: String): String = "reminders_$ownerUserId"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encode(items: List<MessageReminder>): String {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("chatId", r.chatId)
                    .put("messageId", r.messageId)
                    .put("preview", r.messagePreview)
                    .put("remindAt", r.remindAtMillis)
                    .put("createdAt", r.createdAtMillis)
                    // 8.51：持久化 owner——此前 decode 硬编码空串，时钟回拨重排时
                    // Worker 拿不到 owner 导致提醒僵尸化
                    .put("owner", r.ownerUserId)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<MessageReminder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val chatId = obj.optString("chatId").trim()
                    val messageId = obj.optString("messageId").trim()
                    if (id.isEmpty() || chatId.isEmpty() || messageId.isEmpty()) continue
                    // 8.51：8.51 之前写入的旧行无 owner 字段——worker 时钟回拨重排时
                    // 拿不到 owner 会生成僵尸提醒；直接跳过让 upsert 自愈清理
                    if (obj.optString("owner").trim().isEmpty()) continue
                    add(
                        MessageReminder(
                            id = id,
                            chatId = chatId,
                            messageId = messageId,
                            messagePreview = obj.optString("preview").take(80),
                            remindAtMillis = obj.optLong("remindAt", 0L),
                            createdAtMillis = obj.optLong("createdAt", 0L),
                            ownerUserId = obj.optString("owner").trim()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
