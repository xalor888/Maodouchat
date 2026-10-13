package com.maodouchat.server.repository

// bot 内容边界簇：回调数据与 id 长度上限（原 BotValidation 扩展按域拆出，零行为改动）。

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun BotRepository.containsCallbackData(content: String, callbackData: String): Boolean {
    val open = content.lastIndexOf("<meta>")
    val close = content.lastIndexOf("</meta>")
    if (open < 0 || close <= open || close + 7 != content.length) return false
    val raw = content.substring(open + 6, close)
    if (raw.length > MAX_CALLBACK_META_LENGTH) return false
    val root = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull() ?: return false
    val rows = root["inlineKeyboard"] as? JsonArray ?: return false
    return rows.any { rowElement ->
        val row = rowElement as? JsonArray ?: return@any false
        row.any { buttonElement ->
            val button = buttonElement as? JsonObject ?: return@any false
            (button["callbackData"] as? JsonPrimitive)?.content == callbackData
        }
    }
}


internal fun BotRepository.isBoundedId(value: String): Boolean =
    value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }


internal const val MAX_CALLBACK_DATA_LENGTH = 128


internal const val MAX_CALLBACK_META_LENGTH = 16_000
