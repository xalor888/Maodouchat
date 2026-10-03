package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendTableFields(
    val chatId: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)

internal sealed interface BotSendTableFieldsResult {
    data class Ok(val fields: BotSendTableFields) : BotSendTableFieldsResult
    data object MissingRequired : BotSendTableFieldsResult
}

internal fun parseBotSendTableFields(obj: JsonObject): BotSendTableFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：headers 非数组不是错误而是空表头（随即判缺）；单元格 (as? JsonPrimitive)
    // 让对象/数组单元格静默丢弃；显式 null 得字面 "null" 保留为表头；顺序：trim+截 40 →
    // 剔空白 → 取前 8。
    val headersEl = obj["headers"] as? JsonArray
    val headers = headersEl?.mapNotNull {
        (it as? JsonPrimitive)?.content?.trim()?.take(40)
    }?.filter { it.isNotBlank() }?.take(8).orEmpty()
    // 注意：行元素非数组 → 整行静默丢弃；单元格 trim+截 40 但不剔空白（"" 保留）；
    // 每行取 8；整体先剔全空行、后取前 20。
    val rowsEl = obj["rows"] as? JsonArray
    val rows = rowsEl?.mapNotNull { rowEl ->
        val arr = rowEl as? JsonArray ?: return@mapNotNull null
        arr.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.take(40) }
            .take(8)
    }?.filter { it.isNotEmpty() }?.take(20).orEmpty()
    if (chatId.isBlank() || headers.isEmpty() || rows.isEmpty()) {
        return BotSendTableFieldsResult.MissingRequired
    }
    return BotSendTableFieldsResult.Ok(BotSendTableFields(chatId, headers, rows))
}

/**
 * 与原处理器逐字一致的表格消息内容组装：表头行 + 分隔行 + 数据行拼接；列数以
 * `headers.size` 为准（多余单元格丢弃、不足补空串）。
 */
internal fun buildBotTableContent(headers: List<String>, rows: List<List<String>>): String {
    val headLine = "| " + headers.joinToString(" | ") + " |"
    val sepLine = "| " + headers.joinToString(" | ") { "---" } + " |"
    val bodyLines = rows.joinToString("\n") { r ->
        val cells = (0 until headers.size).map { i -> r.getOrNull(i).orEmpty() }
        "| " + cells.joinToString(" | ") + " |"
    }
    return headLine + "\n" + sepLine + "\n" + bodyLines
}
