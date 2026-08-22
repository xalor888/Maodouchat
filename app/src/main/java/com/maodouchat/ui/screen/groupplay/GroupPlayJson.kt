package com.maodouchat.ui.screen.groupplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 群玩法客户端 JSON 兜底：列表端点偶发对象包装、失败体是 `{error}`，
 * 硬 `JSONArray(text)` 会把整页打成空白。
 */
internal object GroupPlayJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun errorMessage(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            obj.stringOrNull("error") ?: obj.stringOrNull("message")
        }.getOrNull()
    }

    /** 返回可被 `org.json.JSONArray` 再解析的数组文本。 */
    fun arrayText(text: String): String = array(text).toString()

    fun array(text: String): JsonArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return JsonArray(emptyList())
        val element = json.parseToJsonElement(trimmed)
        if (element is JsonArray) return element
        val obj = element.jsonObject
        for (key in WRAPPER_KEYS) {
            obj[key]?.let { child ->
                if (child is JsonArray) return child
            }
        }
        if (looksLikeItem(obj)) return JsonArray(listOf(obj))
        error("expected json array")
    }

    private fun looksLikeItem(obj: JsonObject): Boolean =
        obj.containsKey("id") || obj.containsKey("userId") || obj.containsKey("question")

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private val WRAPPER_KEYS = listOf(
        "items", "data", "polls", "pks", "chains", "ranking", "list"
    )
}
