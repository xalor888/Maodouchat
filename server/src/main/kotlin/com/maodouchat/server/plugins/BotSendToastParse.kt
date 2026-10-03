package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendToastFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendToastFieldsResult {
    data class Ok(val fields: BotSendToastFields) : BotSendToastFieldsResult
    data object MissingRequired : BotSendToastFieldsResult
}

internal fun parseBotSendToastFields(obj: JsonObject): BotSendToastFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：别名链是 ?: 接存在性——text 键缺席才穿透到 message；
    // 显式 JSON null 的 text 得字面 "null"（非空白），不穿透、不判缺；
    // 不 trim，前导空格计入 200 上限；take(200) 逐字不变（原处理器逐字顺序）。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(200)
    // 注意：双必填——text 缺省/空白判缺，没有回退默认值（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendToastFieldsResult.MissingRequired
    }
    return BotSendToastFieldsResult.Ok(BotSendToastFields(chatId, text))
}

/**
 * 与原处理器逐字一致的轻提示消息内容组装：`"TOAST: $text"`。
 */
internal fun buildBotToastContent(text: String): String =
    "TOAST: $text"
