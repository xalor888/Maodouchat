package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetChatPermissionsFields(
    val chatId: String,
    val canSend: Boolean,
    val until: Long,
)

internal sealed interface BotSetChatPermissionsFieldsResult {
    data class Ok(val fields: BotSetChatPermissionsFields) : BotSetChatPermissionsFieldsResult
    /** 400 `"chatId/canSendMessages required"` 的全部前件：chatId 缺/空/纯空白，或 canSend 非布尔。 */
    data object Invalid : BotSetChatPermissionsFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 布尔/别名归一化 + 合并必填校验。
 * 三字段端点：`chatId`（无 trim）+ `canSend`（camel 优先、snake 兜底、严格布尔）+
 * `until`（camel 优先、`untilDate` 兜底、缺省 0L）；
 * chatId 缺/空/纯空白，或 canSend 给不出布尔 → Invalid（处理器侧 400
 * `"chatId/canSendMessages required"`）。
 */
internal fun parseBotSetChatPermissionsFields(obj: JsonObject): BotSetChatPermissionsFieldsResult {
    // 注意：三处抽取都在必填判空之前；显式 null chatId → 字面量 "null"（不判空）；
    // 显式 null canSend → booleanOrNull 得 null → 继续看别名。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val canSend = obj["canSendMessages"]?.jsonPrimitive?.booleanOrNull
        ?: obj["can_send_messages"]?.jsonPrimitive?.booleanOrNull
    val until = obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: 0L
    if (chatId.isBlank() || canSend == null) return BotSetChatPermissionsFieldsResult.Invalid
    return BotSetChatPermissionsFieldsResult.Ok(BotSetChatPermissionsFields(chatId, canSend, until))
}
