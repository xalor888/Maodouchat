package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotStarMessageFields(
    val messageId: String,
)

internal sealed interface BotStarMessageFieldsResult {
    data class Ok(val fields: BotStarMessageFields) : BotStarMessageFieldsResult
    data object MissingRequired : BotStarMessageFieldsResult
}

internal fun parseBotStarMessageFields(obj: JsonObject): BotStarMessageFieldsResult {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：无 trim()——判的是原串的空白性（" m1 " 原样通过必填检查进下游）。
    if (messageId.isBlank()) {
        return BotStarMessageFieldsResult.MissingRequired
    }
    return BotStarMessageFieldsResult.Ok(BotStarMessageFields(messageId))
}
