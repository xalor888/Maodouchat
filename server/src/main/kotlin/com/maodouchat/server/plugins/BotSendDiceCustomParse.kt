package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendDiceCustomFields(
    val chatId: String,
    /** 解析并钳制后的面数（2..100）。 */
    val sides: Int,
)

internal sealed interface BotSendDiceCustomFieldsResult {
    data class Ok(val fields: BotSendDiceCustomFields) : BotSendDiceCustomFieldsResult
    data object MissingRequired : BotSendDiceCustomFieldsResult
}

internal fun parseBotSendDiceCustomFields(obj: JsonObject): BotSendDiceCustomFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在 toIntOrNull() 之后——显式 sides 非数字/缺省/显式 null 都直接回 6
    //（与 sendDice 穿透到 emoji 映射默认故意不同，原处理器逐字语义）。
    val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6).coerceIn(2, 100)
    if (chatId.isBlank()) return BotSendDiceCustomFieldsResult.MissingRequired
    return BotSendDiceCustomFieldsResult.Ok(BotSendDiceCustomFields(chatId, sides))
}

/**
 * 与原处理器逐字一致的自定义骰子消息组装：`"DICE:$sides|$value|bot dice roll"`。
 */
internal fun buildBotDiceCustomContent(sides: Int, value: Int): String =
    "DICE:$sides|$value|bot dice roll"
