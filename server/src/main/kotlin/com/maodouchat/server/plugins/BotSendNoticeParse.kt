package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendNoticeFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendNoticeFieldsResult {
    data class Ok(val fields: BotSendNoticeFields) : BotSendNoticeFieldsResult
    data object MissingRequired : BotSendNoticeFieldsResult
}

internal fun parseBotSendNoticeFields(obj: JsonObject): BotSendNoticeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：别名链是 ?: 接存在性——text 键缺席才穿透到 message；
    // 显式 JSON null 的 text 得字面 "null"（非空白），不穿透、不判缺；
    // 不 trim，前导空格计入 300 上限；take(300) 逐字不变（原处理器逐字顺序）。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)
    // 注意：双必填——text 缺省/空白判缺，没有回退默认值（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendNoticeFieldsResult.MissingRequired
    }
    return BotSendNoticeFieldsResult.Ok(BotSendNoticeFields(chatId, text))
}

/**
 * 与原处理器逐字一致的通知消息内容组装：`"NOTICE: $text"`。
 */
internal fun buildBotNoticeContent(text: String): String =
    "NOTICE: $text"
