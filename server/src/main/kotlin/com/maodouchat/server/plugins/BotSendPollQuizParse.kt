package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendPollQuizFields(
    val chatId: String,
    val question: String,
    val options: List<String>,
    val correctOptionIndex: Int,
)

internal sealed interface BotSendPollQuizFieldsResult {
    data class Ok(val fields: BotSendPollQuizFields) : BotSendPollQuizFieldsResult
    data object MissingRequired : BotSendPollQuizFieldsResult
}

internal fun parseBotSendPollQuizFields(obj: JsonObject): BotSendPollQuizFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在字段存在性上——question 键存在（哪怕是显式 JSON null）就不穿透到 text；
    // orEmpty().take(200) 不 trim，前导空格计入上限（原处理器逐字语义）。
    val question = (obj["question"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(200)
    // 注意：as? JsonArray——非数组不是错误而是 emptyList（随即 < 2 判缺）；
    // 元素 (as? JsonPrimitive) 让对象/数组元素静默丢弃，显式 null 得字面 "null" 选项。
    val optionsEl = obj["options"] as? kotlinx.serialization.json.JsonArray
    val options = optionsEl?.mapNotNull {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
    }?.filter { it.isNotBlank() }?.take(10).orEmpty()
    // 注意：correct 抽取在必填检查之前——类型错即使必填缺失也先抛（原处理器逐字顺序）。
    val correct = obj["correctOptionIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    if (chatId.isBlank() || question.isBlank() || options.size < 2) {
        return BotSendPollQuizFieldsResult.MissingRequired
    }
    return BotSendPollQuizFieldsResult.Ok(BotSendPollQuizFields(chatId, question, options, correct))
}

/**
 * 与原处理器逐字一致的测验消息内容组装：`"QUIZ:"` + question（不 trim 原样）+
 * 每个选项 `"|"` 前缀、正确选项多一个 `"*"` 标记，整体 `take(2000)`。
 */
internal fun buildBotPollQuizContent(question: String, options: List<String>, safeIdx: Int): String =
    buildString {
        append("QUIZ:").append(question)
        options.forEachIndexed { i, o ->
            append("|").append(if (i == safeIdx) "*" else "").append(o)
        }
    }.take(2000)
