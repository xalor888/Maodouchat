package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class BotExportChatInviteLinkFields(
    val chatId: String,
    val rotate: Boolean,
    val expiresInSeconds: Long,
    val maxUses: Int,
)

internal sealed interface BotExportChatInviteLinkFieldsResult {
    data class Ok(val fields: BotExportChatInviteLinkFields) : BotExportChatInviteLinkFieldsResult
    data object MissingRequired : BotExportChatInviteLinkFieldsResult
}

/** 缺省与夹界常量（逐字取自原处理器，测试直接钉住同一份语义）。 */
internal const val BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS = 7L * 24 * 3600
internal const val BOT_EXPORT_CHAT_INVITE_MIN_EXPIRES_IN_SECONDS = 300L
internal const val BOT_EXPORT_CHAT_INVITE_MAX_EXPIRES_IN_SECONDS = 30L * 24 * 3600
internal const val BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES = 100
internal const val BOT_EXPORT_CHAT_INVITE_MIN_MAX_USES = 1
internal const val BOT_EXPORT_CHAT_INVITE_MAX_MAX_USES = 1000

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `rotate` → `expiresInSeconds` → `maxUses`，最后判 `chatId.isBlank()`。
 */
internal fun parseBotExportChatInviteLinkFields(obj: JsonObject): BotExportChatInviteLinkFieldsResult {
    // 注意：chatId 无 trim；rotate 严格判真（== true）；expiresInSeconds/maxUses
    // 先取缺省后夹界；只有 chatId 参与必填。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val rotate = obj["rotate"]?.jsonPrimitive?.booleanOrNull == true
    val expiresInSeconds = (obj["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS)
        .coerceIn(BOT_EXPORT_CHAT_INVITE_MIN_EXPIRES_IN_SECONDS, BOT_EXPORT_CHAT_INVITE_MAX_EXPIRES_IN_SECONDS)
    val maxUses = (obj["maxUses"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES)
        .coerceIn(BOT_EXPORT_CHAT_INVITE_MIN_MAX_USES, BOT_EXPORT_CHAT_INVITE_MAX_MAX_USES)
    if (chatId.isBlank()) return BotExportChatInviteLinkFieldsResult.MissingRequired
    return BotExportChatInviteLinkFieldsResult.Ok(
        BotExportChatInviteLinkFields(chatId, rotate, expiresInSeconds, maxUses)
    )
}
