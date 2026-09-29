package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendPoll` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue` 之后**第十一块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPollRoutes` 的 `/api/bot/sendPoll` 处理器里的**抽取 / 校验 /
 * 摘要组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `options` 用 `is JsonArray` 分支判断——非数组（字符串/对象/缺省）**不是错误**，
 *   而是 `emptyList()`（随即因 `< 2` 判 `MissingRequired`）；数组元素用
 *   `runCatching { it.jsonPrimitive.content }`：非原始类型元素被静默丢弃，
 *   但显式 JSON null 是 `JsonPrimitive`、`.content` 为 `"null"` 字符串——**保留为
 *   字面 `"null"` 选项**（逐字语义，特意钉住）；
 * - 元素 `trim()` 后空白剔除（`takeIf { it.isNotBlank() }`），而 `question` 本身
 *   **不 trim**（`.content.orEmpty()` 原样）——不对称逐字保留；
 * - 布尔别名链 `multi`→`allowsMultipleAnswers`、`anonymous`→`isAnonymous` 用
 *   `?.jsonPrimitive?.booleanOrNull` 接 `?:`——主字段非布尔字符串（如 `"yes"`）
 *   时 `booleanOrNull` 为 null 即**穿透到别名**，与坐标字段的「穿透」同一族语义；
 *   缺省 `multi = false`、`anonymous = true`（Telegram 语义：投票默认匿名）；
 * - `closesAt`→`closeDate` 用 `?.content?.toLongOrNull()` 接 `?:`——非数字同样穿透；
 *   整条链取不到才为 null；
 * - 校验顺序刻意与原处理器一致（必填 → 成员检查（处理器）→ 群玩法开关（处理器）），
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
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
