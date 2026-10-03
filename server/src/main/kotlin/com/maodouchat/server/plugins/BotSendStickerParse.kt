package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendStickerFields(
    val chatId: String,
    val emoji: String,
    val pack: String,
)

internal sealed interface BotSendStickerFieldsResult {
    data class Ok(val fields: BotSendStickerFields) : BotSendStickerFieldsResult
    data object MissingRequired : BotSendStickerFieldsResult
}

/** 与原处理器逐字一致的 `sendSticker` 字段抽取（含别名优先级与截断）。 */
internal fun parseBotSendStickerFields(obj: JsonObject): BotSendStickerFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val emoji = (obj["emoji"] ?: obj["sticker"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().trim().take(16)
    val pack = obj["pack"]?.jsonPrimitive?.content.orEmpty().trim().take(40)
    if (chatId.isBlank() || emoji.isBlank()) return BotSendStickerFieldsResult.MissingRequired
    return BotSendStickerFieldsResult.Ok(BotSendStickerFields(chatId, emoji, pack))
}

/** 与原处理器逐字一致的 STICKER 消息内容组装（emoji + 可选的 pack 段）。 */
internal fun buildBotStickerContent(emoji: String, pack: String): String =
    buildString {
        append(emoji)
        if (pack.isNotBlank()) {
            append("\n[stickerPack:")
            append(pack)
            append("]")
        }
    }
