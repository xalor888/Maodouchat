package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotReactionFields(
    val messageId: String,
    val emoji: String,
)

internal sealed interface BotReactionFieldsResult {
    data class Ok(val fields: BotReactionFields) : BotReactionFieldsResult
    data object MissingRequired : BotReactionFieldsResult
    data object UnsupportedEmoji : BotReactionFieldsResult
}

internal fun parseBotReactionFields(obj: JsonObject): BotReactionFieldsResult {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    val emoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().trim()
    // 注意：双必填先于白名单——判的是 trim() 之后的空白性。
    if (messageId.isBlank() || emoji.isBlank()) {
        return BotReactionFieldsResult.MissingRequired
    }
    if (emoji !in ALLOWED_REACTION_EMOJIS) {
        return BotReactionFieldsResult.UnsupportedEmoji
    }
    return BotReactionFieldsResult.Ok(BotReactionFields(messageId, emoji))
}
