package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendDiceFields(
    val chatId: String,
    /** emoji 字段原样值（未 trim）；空白/缺省为 null。 */
    val diceEmoji: String?,
    /** 解析并钳制后的面数（2..100）。 */
    val sides: Int,
)

internal sealed interface BotSendDiceFieldsResult {
    data class Ok(val fields: BotSendDiceFields) : BotSendDiceFieldsResult
    data object MissingRequired : BotSendDiceFieldsResult
}

internal fun parseBotSendDiceFields(obj: JsonObject): BotSendDiceFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // emoji 语义映射（对齐 Telegram 骰子）：🎲🎯🎳 6 面、🏀⚽ 5 面、🎰 64 面；显式 sides 优先。
    // 注意：emoji 不 trim，when 是全字串比较——逐字语义。
    val diceEmoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().takeIf { it.isNotBlank() }
    // 注意：?: 接在 toIntOrNull() 之后——显式 sides 非数字时穿透到 emoji 映射默认（原处理器逐字语义）。
    val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: when (diceEmoji) {
            "🏀", "⚽" -> 5
            "🎰" -> 64
            else -> 6
        }).coerceIn(2, 100)
    if (chatId.isBlank()) return BotSendDiceFieldsResult.MissingRequired
    return BotSendDiceFieldsResult.Ok(BotSendDiceFields(chatId, diceEmoji, sides))
}

/**
 * 与原处理器逐字一致的骰子消息组装：`"{emoji ?: 🎲} {value}/{sides}"`。
 */
internal fun buildBotDiceContent(diceEmoji: String?, value: Int, sides: Int): String =
    "${diceEmoji ?: "🎲"} $value/$sides"
