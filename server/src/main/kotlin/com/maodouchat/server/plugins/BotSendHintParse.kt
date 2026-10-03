package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendHintFields(
    val chatId: String,
    val hint: String,
)

internal sealed interface BotSendHintFieldsResult {
    data class Ok(val fields: BotSendHintFields) : BotSendHintFieldsResult
    data object MissingRequired : BotSendHintFieldsResult
}

/**
 * 十二个 `send*Hint` 端点共用的请求体解析。
 *
 * [defaultHint] 为各端点的默认提示文案（键缺席时回退）。抽取顺序与原处理器逐字一致：
 * 先 `chatId`、再 `hint`，最后判必填——坏类型字段的抛错顺序也因此不变。
 */
internal fun parseBotSendHintFields(obj: JsonObject, defaultHint: String): BotSendHintFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接存在性——hint 键缺席才回 defaultHint；显式 JSON null 得字面 "null"（非 null，不回退）；
    // 不 trim，前导空格计入 take(120) 上限（原处理器逐字顺序）。
    val hint = (obj["hint"]?.jsonPrimitive?.content ?: defaultHint).take(120)
    // 注意：单必填——hint 缺省/空白不判缺（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank()) {
        return BotSendHintFieldsResult.MissingRequired
    }
    return BotSendHintFieldsResult.Ok(BotSendHintFields(chatId, hint))
}

/**
 * 与原处理器逐字一致的内容组装：`prefix + hint`（`type = "SYSTEM"` 由处理器固定）。
 */
internal fun buildBotSendHintContent(prefix: String, hint: String): String = prefix + hint
