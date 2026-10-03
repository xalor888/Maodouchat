package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendPollFields(
    val chatId: String,
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
    val closesAt: Long?,
)

internal sealed interface BotSendPollFieldsResult {
    data class Ok(val fields: BotSendPollFields) : BotSendPollFieldsResult
    data object MissingRequired : BotSendPollFieldsResult
}

internal fun parseBotSendPollFields(obj: JsonObject): BotSendPollFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
    val optionsEl = obj["options"]
    val options = when (optionsEl) {
        is kotlinx.serialization.json.JsonArray -> optionsEl.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()?.trim()?.takeIf { s -> s.isNotBlank() }
        }
        else -> emptyList()
    }
    // 注意：?: 接在 booleanOrNull 之后——主字段非布尔值时穿透到别名（原处理器逐字语义）。
    val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
        ?: obj["allowsMultipleAnswers"]?.jsonPrimitive?.booleanOrNull
        ?: false
    val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
        ?: obj["isAnonymous"]?.jsonPrimitive?.booleanOrNull
        ?: true
    val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj["closeDate"]?.jsonPrimitive?.content?.toLongOrNull()
    if (chatId.isBlank() || question.isBlank() || options.size < 2) {
        return BotSendPollFieldsResult.MissingRequired
    }
    return BotSendPollFieldsResult.Ok(BotSendPollFields(chatId, question, options, multi, anonymous, closesAt))
}

/**
 * 与原处理器逐字一致的投票摘要组装：`"📊 "` + question + 按行 `"{i+1}. {option}"` +
 * `"\n[poll:{pollId}]"`。
 */
internal fun buildBotPollSummary(question: String, options: List<String>, pollId: String): String =
    buildString {
        append("📊 ")
        append(question)
        options.forEachIndexed { i, o ->
            append("\n")
            append(i + 1)
            append(". ")
            append(o)
        }
        append("\n[poll:")
        append(pollId)
        append("]")
    }
