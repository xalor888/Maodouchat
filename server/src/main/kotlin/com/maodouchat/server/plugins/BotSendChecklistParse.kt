package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendChecklistFields(
    val chatId: String,
    val title: String,
    val items: List<String>,
)

internal sealed interface BotSendChecklistFieldsResult {
    data class Ok(val fields: BotSendChecklistFields) : BotSendChecklistFieldsResult
    data object MissingRequired : BotSendChecklistFieldsResult
}

internal fun parseBotSendChecklistFields(obj: JsonObject): BotSendChecklistFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：title 不 trim，前导空格计入 80 上限（原处理器逐字语义）；显式 JSON null
    // 得字面 "null"——不是缺省（title 非必填，空白只影响组装前缀）。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().take(80)
    // 注意：as? JsonArray——非数组不是错误而是 emptyList（随即判缺）；
    // 元素 (as? JsonPrimitive) 让对象/数组元素静默丢弃，显式 null 得字面 "null" 选项。
    val itemsEl = obj["items"] as? kotlinx.serialization.json.JsonArray
    val items = itemsEl?.mapNotNull {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
    }?.filter { it.isNotBlank() }?.take(20).orEmpty()
    if (chatId.isBlank() || items.isEmpty()) {
        return BotSendChecklistFieldsResult.MissingRequired
    }
    return BotSendChecklistFieldsResult.Ok(BotSendChecklistFields(chatId, title, items))
}

/**
 * 与原处理器逐字一致的清单消息内容组装：标题非空时 `"**title**\n"` + 每项
 * `"- [ ] item"` 换行拼接。无整体截断（原处理器逐字如此）。
 */
internal fun buildBotChecklistContent(title: String, items: List<String>): String {
    val head = if (title.isNotBlank()) "**$title**\n" else ""
    val bodyMd = items.joinToString("\n") { "- [ ] $it" }
    return head + bodyMd
}
