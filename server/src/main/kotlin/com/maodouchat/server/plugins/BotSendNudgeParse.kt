package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendNudgeFields(
    val chatId: String,
    val note: String,
)

internal sealed interface BotSendNudgeFieldsResult {
    data class Ok(val fields: BotSendNudgeFields) : BotSendNudgeFieldsResult
    data object MissingRequired : BotSendNudgeFieldsResult
}

/** 与原处理器逐字一致的 `sendNudge` 字段抽取（含 80 截断与 chatId 单必填）。 */
internal fun parseBotSendNudgeFields(obj: JsonObject): BotSendNudgeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：原处理器对 text 只做 take(80)、不 trim，逐字保留。
    val note = obj["text"]?.jsonPrimitive?.content.orEmpty().take(80)
    if (chatId.isBlank()) return BotSendNudgeFieldsResult.MissingRequired
    return BotSendNudgeFieldsResult.Ok(BotSendNudgeFields(chatId, note))
}

/** 与原处理器逐字一致的 NUDGE 消息内容组装（空 note 回退为 `"👋 nudge"`）。 */
internal fun buildBotNudgeContent(note: String): String =
    if (note.isNotBlank()) "👋 $note" else "👋 nudge"
