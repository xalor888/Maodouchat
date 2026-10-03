package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendStepsFields(
    val chatId: String,
    val title: String,
    val steps: List<String>,
)

internal sealed interface BotSendStepsFieldsResult {
    data class Ok(val fields: BotSendStepsFields) : BotSendStepsFieldsResult
    /** 400 `"chatId/steps required"` 的全部前件：chatId 缺/空/纯空白，或 steps 缺/空/全被丢弃。 */
    data object Invalid : BotSendStepsFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * `chatId`（无 trim）；`title`（缺键→`"Steps"`，逐项取 80）；`steps`（逐项取 160、全表取 20，
 * 非 primitive 元素静默丢弃）——缺/空/纯空白 chatId 或空 steps → Invalid
 * （处理器侧 400 `"chatId/steps required"`）。
 */
internal fun parseBotSendStepsFields(obj: JsonObject): BotSendStepsFieldsResult {
    // 注意：无 trim；chatId 键显式 null → 字面量 "null"（不判空）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：title 缺键才回退 "Steps"；显式 null → 字面量 "null"（不回退）。
    val title = (obj["title"]?.jsonPrimitive?.content ?: "Steps").take(80)
    // 注意：steps 键显式 null / 非数组型在 ?.jsonArray 处大声失败；
    // 数组里的对象/数组元素静默丢弃（runCatching），JsonNull 元素得 "null" 字符串。
    val steps = (obj["steps"]?.jsonArray?.mapNotNull {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    } ?: emptyList()).map { it.take(160) }.take(20)
    if (chatId.isBlank() || steps.isEmpty()) return BotSendStepsFieldsResult.Invalid
    return BotSendStepsFieldsResult.Ok(BotSendStepsFields(chatId, title, steps))
}
