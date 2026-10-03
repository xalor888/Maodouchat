package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetChatPhotoFields(
    val chatId: String,
    val base64: String,
)

internal sealed interface BotSetChatPhotoFieldsResult {
    data class Ok(val fields: BotSetChatPhotoFields) : BotSetChatPhotoFieldsResult
    data object MissingRequired : BotSetChatPhotoFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId` + `base64`（三键回退，无缺省/夹界逻辑）。
 */
internal fun parseBotSetChatPhotoFields(obj: JsonObject): BotSetChatPhotoFieldsResult {
    // 注意：两处均无 trim；按「存在」回退，photoBase64 在场但为空时不再回退。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val base64 = (obj["photoBase64"] ?: obj["base64Data"] ?: obj["photo"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || base64.isBlank()) return BotSetChatPhotoFieldsResult.MissingRequired
    return BotSetChatPhotoFieldsResult.Ok(BotSetChatPhotoFields(chatId, base64))
}
