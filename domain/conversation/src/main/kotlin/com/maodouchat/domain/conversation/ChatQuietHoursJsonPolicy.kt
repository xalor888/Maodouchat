package com.maodouchat.domain.conversation

import com.maodouchat.core.model.QuietWindow
import org.json.JSONObject

// 时段窗口与临时静音共存于同一条目；silentUntilMs <= 0 表示未设置。
data class ChatQuietHoursEntry(
    val window: QuietWindow,
    val silentUntilMs: Long = 0L
)

// 会话免打扰时段的整串 JSON 编解码：Map<chatId, 条目> <-> 字符串。
object ChatQuietHoursJsonPolicy {
    fun decodeEntries(raw: String?): Map<String, ChatQuietHoursEntry> = runCatching {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = JSONObject(raw)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val chatId = keys.next()
                val entryObj = obj.optJSONObject(chatId) ?: continue
                val window = QuietWindow(
                    enabled = entryObj.optBoolean("enabled", false),
                    startMinute = entryObj.optInt("start", 0).coerceIn(0, 1439),
                    endMinute = entryObj.optInt("end", 0).coerceIn(0, 1439)
                )
                val silentUntil = entryObj.optLong("silent_until", 0L).takeIf { it > 0L } ?: 0L
                put(chatId, ChatQuietHoursEntry(window, silentUntil))
            }
        }
    }.getOrDefault(emptyMap())

    fun encodeEntries(entries: Map<String, ChatQuietHoursEntry>): String {
        val obj = JSONObject()
        entries.forEach { (chatId, entry) ->
            val json = JSONObject()
                .put("enabled", entry.window.enabled)
                .put("start", entry.window.startMinute.coerceIn(0, 1439))
                .put("end", entry.window.endMinute.coerceIn(0, 1439))
            if (entry.silentUntilMs > 0L) json.put("silent_until", entry.silentUntilMs)
            obj.put(chatId, json)
        }
        return obj.toString()
    }

    fun withQuietWindow(
        entries: Map<String, ChatQuietHoursEntry>,
        chatId: String,
        window: QuietWindow
    ): Map<String, ChatQuietHoursEntry> {
        if (chatId.isBlank()) return entries
        val existing = entries[chatId]
        return entries + (chatId to ChatQuietHoursEntry(window, existing?.silentUntilMs ?: 0L))
    }

    fun withSilentUntil(
        entries: Map<String, ChatQuietHoursEntry>,
        chatId: String,
        untilMs: Long
    ): Map<String, ChatQuietHoursEntry> {
        if (chatId.isBlank()) return entries
        val existing = entries[chatId]
        val silentUntil = if (untilMs <= 0L) 0L else untilMs
        val window = existing?.window ?: QuietWindow.OFF
        if (window == QuietWindow.OFF && silentUntil == 0L) return entries - chatId
        return entries + (chatId to ChatQuietHoursEntry(window, silentUntil))
    }

    fun withoutChat(
        entries: Map<String, ChatQuietHoursEntry>,
        chatId: String
    ): Map<String, ChatQuietHoursEntry> = entries - chatId

    fun quietWindow(entries: Map<String, ChatQuietHoursEntry>, chatId: String): QuietWindow =
        entries[chatId]?.window ?: QuietWindow.OFF

    fun silentUntil(entries: Map<String, ChatQuietHoursEntry>, chatId: String): Long =
        entries[chatId]?.silentUntilMs ?: 0L
}
