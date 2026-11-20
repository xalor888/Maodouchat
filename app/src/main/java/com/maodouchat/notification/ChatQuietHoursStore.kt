package com.maodouchat.notification

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.core.model.QuietWindow
import com.maodouchat.domain.conversation.ChatQuietHoursJsonPolicy
import com.maodouchat.util.currentUserId

/**
 * 会话级「免打扰时段」（8.46）：按账号本地保存每个会话的静音时间窗，
 * 与全局 DND 互补（TG 风格 per-chat schedule）。纯本机功能，不触碰服务端。
 */
object ChatQuietHoursStore {

    private const val PREFS_NAME = "chat_quiet_hours"
    private const val KEY_QUIET_HOURS = "quiet_hours"

    // 读-改-写整串 JSON 必须互斥：UI 设置时段与 FCM 推送路径并发时，
    // 后写者整串覆盖会让先写者的修改静默丢失（竞态）。
    private val lock = Any()

    fun get(context: Context, chatId: String): QuietWindow {
        val userId = currentUserId(context) ?: return QuietWindow.OFF
        if (chatId.isBlank()) return QuietWindow.OFF
        val raw = prefs(context).getString(key(userId), null)
        return ChatQuietHoursJsonPolicy.quietWindow(ChatQuietHoursJsonPolicy.decodeEntries(raw), chatId)
    }

    fun set(context: Context, chatId: String, window: QuietWindow) {
        val userId = currentUserId(context) ?: return
        if (chatId.isBlank()) return
        synchronized(lock) {
            runCatching {
                val raw = prefs(context).getString(key(userId), null)
                val entries = ChatQuietHoursJsonPolicy.withQuietWindow(
                    ChatQuietHoursJsonPolicy.decodeEntries(raw), chatId, window
                )
                prefs(context).edit { putString(key(userId), ChatQuietHoursJsonPolicy.encodeEntries(entries)) }
            }
        }
    }

    /** 清除该会话的免打扰时段（enabled=false 亦可表达关闭，此处物理删除）。 */
    fun remove(context: Context, chatId: String) {
        val userId = currentUserId(context) ?: return
        if (chatId.isBlank()) return
        synchronized(lock) {
            runCatching {
                val raw = prefs(context).getString(key(userId), null) ?: return@runCatching
                val entries = ChatQuietHoursJsonPolicy.decodeEntries(raw)
                if (entries.containsKey(chatId)) {
                    prefs(context).edit {
                        putString(key(userId), ChatQuietHoursJsonPolicy.encodeEntries(ChatQuietHoursJsonPolicy.withoutChat(entries, chatId)))
                    }
                }
            }
        }
    }

    fun setSilentUntil(context: Context, chatId: String, untilMs: Long) {
        val userId = currentUserId(context) ?: return
        if (chatId.isBlank()) return
        synchronized(lock) {
            runCatching {
                val raw = prefs(context).getString(key(userId), null)
                val entries = ChatQuietHoursJsonPolicy.withSilentUntil(
                    ChatQuietHoursJsonPolicy.decodeEntries(raw), chatId, untilMs
                )
                prefs(context).edit { putString(key(userId), ChatQuietHoursJsonPolicy.encodeEntries(entries)) }
            }
        }
    }

    fun silentUntil(context: Context, chatId: String): Long {
        val userId = currentUserId(context) ?: return 0L
        if (chatId.isBlank()) return 0L
        val raw = prefs(context).getString(key(userId), null)
        return ChatQuietHoursJsonPolicy.silentUntil(ChatQuietHoursJsonPolicy.decodeEntries(raw), chatId)
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit { remove(key(userId)) }
    }


    private fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(userId: String): String = "${KEY_QUIET_HOURS}_$userId"
}
