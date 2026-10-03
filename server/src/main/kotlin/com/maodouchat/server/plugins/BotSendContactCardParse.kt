package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendContactCardFields(
    val chatId: String,
    val name: String,
)

internal sealed interface BotSendContactCardFieldsResult {
    data class Ok(val fields: BotSendContactCardFields) : BotSendContactCardFieldsResult
    data object MissingRequired : BotSendContactCardFieldsResult
}

/** 与原处理器逐字一致的 `sendContactCard` 字段抽取（含 80 截断与 chatId 单必填）。 */
internal fun parseBotSendContactCardFields(obj: JsonObject): BotSendContactCardFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：原处理器对 name 只做 take(80)、不 trim；缺失回 "contact"，逐字保留。
    val name = (obj["name"]?.jsonPrimitive?.content ?: "contact").take(80)
    if (chatId.isBlank()) return BotSendContactCardFieldsResult.MissingRequired
    return BotSendContactCardFieldsResult.Ok(BotSendContactCardFields(chatId, name))
}

/** 与原处理器逐字一致的联系人名片消息内容组装（模板 `"> ~card:$name~"`）。 */
internal fun buildBotContactCardContent(name: String): String = "> ~card:$name~"
