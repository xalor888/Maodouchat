package com.maodouchat.ui.screen.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * 开发者 Bot 页的 JSON 解析簇（`tokenOnce` 提取 / Bot 数组提取 / `BotUi` 解析），
 * 从 DeveloperBotsScreen.kt 按簇搬出。
 */

internal data class BotUi(
    val id: String,
    val name: String,
    val username: String,
    val tokenPrefix: String,
    val webhookUrl: String,
    val enabled: Boolean,
    val tokenOnce: String = ""
)


internal fun extractTokenOnce(raw: String?): String? {
    val text = raw.orEmpty().trim()
    if (text.isEmpty()) return null
    runCatching { JSONObject(text) }.getOrNull()?.let { obj ->
        obj.optString("tokenOnce").takeIf { it.isNotBlank() }?.let { return it }
        obj.optJSONObject("data")?.optString("tokenOnce")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.optJSONObject("bot")?.optString("tokenOnce")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    runCatching { JSONArray(text) }.getOrNull()?.optJSONObject(0)
        ?.optString("tokenOnce")?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

internal fun extractBotArray(raw: String): JSONArray? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    runCatching { JSONArray(trimmed) }.getOrNull()?.let { return it }
    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    listOf("bots", "data", "items", "content").forEach { key ->
        obj.optJSONArray(key)?.let { return it }
    }
    if (obj.has("id")) return JSONArray().put(obj)
    return null
}

internal fun parseBots(raw: String): List<BotUi> {
    val arr = extractBotArray(raw) ?: return emptyList()
    val seen = HashSet<String>()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank() || !seen.add(id)) continue
            val username = o.optString("username").trim()
            add(
                BotUi(
                    id = id,
                    name = o.optString("name").trim().ifBlank { username.ifBlank { id } },
                    username = username,
                    tokenPrefix = o.optString("tokenPrefix"),
                    webhookUrl = o.optString("webhookUrl"),
                    enabled = o.optBoolean("enabled", true),
                    tokenOnce = o.optString("tokenOnce")
                )
            )
        }
    }
}

