package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotAnswerCallbackQueryFields(
    val callbackQueryId: String,
    val text: String?,
)

/**
 * 与原处理器逐行一致的 `answerCallbackQuery` 字段抽取（`callbackQueryId`→`id`
 * 别名链、`text` take(200) 不 trim、无必填校验）。
 */
internal fun parseBotAnswerCallbackQueryFields(obj: JsonObject): BotAnswerCallbackQueryFields {
    // 注意：?: 接在字段存在性上——callbackQueryId 键存在（哪怕是显式 JSON null）
    // 就不穿透到 id，得字面 "null"（原处理器逐字语义）；两键都缺才回 ""。
    val callbackQueryId = obj["callbackQueryId"]?.jsonPrimitive?.content
        ?: obj["id"]?.jsonPrimitive?.content
        ?: ""
    // 注意：take(200) 不 trim，前导空格计入上限（原处理器逐字语义）；缺省回 null
    //（不是 ""）——"" 的回退在处理器组装响应体时发生。
    val text = obj["text"]?.jsonPrimitive?.content?.take(200)
    return BotAnswerCallbackQueryFields(callbackQueryId, text)
}
