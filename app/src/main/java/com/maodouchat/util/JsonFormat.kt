package com.maodouchat.util

import com.maodouchat.core.model.MessageMetaJson
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把任何 Map/List/标量 转成紧凑 JSON 字符串（无 indent）
 */
object JsonFormat {
    private const val META_TAG_SUFFIX = "</meta>"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encode(value: Any?): String = json.encodeToString(JsonElement.serializer(), toJsonElement(value))
    fun encodePretty(value: Any?): String = prettyJson.encodeToString(JsonElement.serializer(), toJsonElement(value))

    /** Encode MessageMeta as a JSON object (never data-class toString()). */
    fun encodeMessageMeta(meta: MessageMeta): String = MessageMetaJson.encodeMessageMeta(meta)

    /**
     * 9.144：统一的「正文 + <meta> 标签」编码入口。
     * 此前 ViewModel / AiMessageMetaSyncRepository / ImageOcrAutoIndexer 各持一份手写
     * 副本，分别漏掉 forwardedFrom / viewOnce / silent / spoilerMedia 等字段，跨设备
     * AI meta 同步与 OCR 回写落库时会把隐私标志静默清除——所有副本一律委托到此处。
     *
     * 8.49：`<meta>` / `</meta>` 字面量是协议保留字——发送方完全可控的字符串值
     * （fileName / forwardedFrom 等）携带字面量会破坏 parsedContent/parsedMeta 的
     * 定位解析（正文截断或整段 meta 丢弃），编码前一律剥离。
     */
    fun composeContentWithMeta(text: String, meta: MessageMeta): String {
        val map = messageMetaMap(meta)
        val safeText = stripMetaTagLiterals(text)
        if (map.values.all(::isDefaultMetaValue)) return safeText
        val encoded = stripMetaTagLiterals(encode(map))
        return safeText + Message.META_TAG_PREFIX + encoded + META_TAG_SUFFIX
    }

    private fun stripMetaTagLiterals(value: String): String =
        if (!value.contains(META_TAG_SUFFIX) && !value.contains(Message.META_TAG_PREFIX)) value
        else value.replace(META_TAG_SUFFIX, "").replace(Message.META_TAG_PREFIX, "")

    private fun isDefaultMetaValue(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Boolean -> !value
        is List<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    fun messageMetaMap(meta: MessageMeta): Map<String, Any?> = MessageMetaJson.messageMetaMap(meta)

    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> value
        is MessageMeta -> toJsonElement(messageMetaMap(value))
        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
        )
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /** 从一段 JSON 字符串解析出 MessageMeta */
    fun fromJsonString(text: String): MessageMeta = MessageMetaJson.fromJsonString(text)
}
