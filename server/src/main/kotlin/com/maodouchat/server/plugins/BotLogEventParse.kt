package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class BotLogEventFields(
    val event: String,
    val chatId: String?,
    val userId: String?,
)

internal sealed interface BotLogEventFieldsResult {
    data class Ok(val fields: BotLogEventFields) : BotLogEventFieldsResult
    data object InvalidType : BotLogEventFieldsResult
}

/** 与原处理器逐字一致的 `logEvent` 字段抽取（含 event/name 别名、40 截断、trim 后置空）。 */
internal fun parseBotLogEventFields(obj: JsonObject): BotLogEventFieldsResult {
    fun stringField(name: String): String? =
        (obj[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    // 显式给出的四个字段必须全是 JSON 字符串：显式 null/数字/对象/数组都大声 400。
    if (listOf("event", "name", "chatId", "userId").any { name -> obj[name] != null && stringField(name) == null }) {
        return BotLogEventFieldsResult.InvalidType
    }
    val event = (stringField("event") ?: stringField("name")).orEmpty().take(40).ifBlank { "custom" }
    val chatId = stringField("chatId")?.trim()?.takeIf { it.isNotEmpty() }
    val userId = stringField("userId")?.trim()?.takeIf { it.isNotEmpty() }
    return BotLogEventFieldsResult.Ok(BotLogEventFields(event, chatId, userId))
}
