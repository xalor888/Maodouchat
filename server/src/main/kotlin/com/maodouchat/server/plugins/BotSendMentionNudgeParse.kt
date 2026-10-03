package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotMentionNudgeFields(
    val chatId: String,
    val label: String,
)

/**
 * 两个 mention/nudge 卡片端点共用的请求体解析。
 *
 * [defaultLabel] 为各端点的默认文案（`label` 键与 `text` 键都缺席时才回退；显式 JSON
 * null 得字面 `"null"`，不继续回退）。抽取顺序与原处理器逐字一致：先 `chatId`、
 * 再 `label`（经 `text` 回退链），最后判必填——坏类型字段的抛错顺序也因此不变。
 */
internal sealed interface BotMentionNudgeFieldsResult {
    data class Ok(val fields: BotMentionNudgeFields) : BotMentionNudgeFieldsResult
    data object MissingRequired : BotMentionNudgeFieldsResult
}

internal fun parseBotMentionNudgeFields(
    obj: JsonObject,
    defaultLabel: String,
): BotMentionNudgeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：回退链接存在性——显式 JSON null 得字面 "null"（非 null，不继续回退）；
    // 不 trim，前导空格计入 take(80) 上限（原处理器逐字顺序）。
    val label = (obj["label"]?.jsonPrimitive?.content ?: obj["text"]?.jsonPrimitive?.content ?: defaultLabel).take(80)
    // 注意：单必填——label 缺省/空白不判缺（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank()) {
        return BotMentionNudgeFieldsResult.MissingRequired
    }
    return BotMentionNudgeFieldsResult.Ok(BotMentionNudgeFields(chatId, label))
}

/**
 * 与原处理器逐字一致的内容组装：`prefix + label + suffix`
 * （`sendMentionCard`: `"> @"` + label；`sendNudgeCard`: `"> ~nudge:"` + label + `"~"`）。
 */
internal fun buildBotMentionNudgeContent(prefix: String, label: String, suffix: String): String =
    prefix + label + suffix
