package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotRevokeChatInviteLinkFields(
    val chatId: String,
)

internal sealed interface BotRevokeChatInviteLinkFieldsResult {
    data class Ok(val fields: BotRevokeChatInviteLinkFields) : BotRevokeChatInviteLinkFieldsResult
    data object MissingRequired : BotRevokeChatInviteLinkFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 单字段端点：只有 `chatId`（无缺省/夹界逻辑）。
 */
internal fun parseBotRevokeChatInviteLinkFields(obj: JsonObject): BotRevokeChatInviteLinkFieldsResult {
    // 注意：chatId 无 trim；缺/空/纯空白 → MissingRequired（处理器侧 400 "chatId required"）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank()) return BotRevokeChatInviteLinkFieldsResult.MissingRequired
    return BotRevokeChatInviteLinkFieldsResult.Ok(BotRevokeChatInviteLinkFields(chatId))
}
