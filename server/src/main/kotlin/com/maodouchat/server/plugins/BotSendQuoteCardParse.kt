package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendQuoteCard` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue` 之后**第三十二块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendQuoteCard`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotQuoteCardFields]，
 * 内容组装收敛为 [buildBotQuoteCardContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `quote` 取 `obj["quote"]?.jsonPrimitive?.content.orEmpty().take(200)`——
 *   **无默认值**：键缺席得 `""`（随后被双必填判缺），键在但空白原样保留空白
 *   （同样判缺）；显式 JSON null 得字面 `\"null\"`（`JsonNull` 本身是
 *   `JsonPrimitive`，`.content` 为 `\"null\"`，**不**被 `orEmpty` 吞掉，
 *   特意钉住）；
 * - `by` 取 `obj["by"]?.jsonPrimitive?.content.orEmpty().take(40)`——
 *   **无默认值、非必填**：缺席得 `""`，空白原样保留；组装时空白 `by`
 *   直接吞掉署名行（`attribution = ""`），原处理器逐字如此，特意钉住；
 * - **双必填**（`chatId.isBlank() || quote.isBlank()`→400
 *   `\"chatId/quote required\"`）；`by` 非必填；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotQuoteCardFields(
    val chatId: String,
    val quote: String,
    val by: String,
)

internal sealed interface BotQuoteCardFieldsResult {
    data class Ok(val fields: BotQuoteCardFields) : BotQuoteCardFieldsResult
    data object MissingRequired : BotQuoteCardFieldsResult
}

/**
 * `sendQuoteCard` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `quote`、最后 `by`，然后判必填——
 * 坏类型字段的抛错顺序也因此不变（对象/数组型已知字段的 `?.jsonPrimitive`
 * 抛 [IllegalArgumentException]，大声失败）。
 */
internal fun parseBotQuoteCardFields(obj: JsonObject): BotQuoteCardFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val quote = obj["quote"]?.jsonPrimitive?.content.orEmpty().take(200)
    val by = obj["by"]?.jsonPrimitive?.content.orEmpty().take(40)
    // 注意：双必填——其余字段缺省/空白不判缺（原处理器逐字如此）。
    if (chatId.isBlank() || quote.isBlank()) {
        return BotQuoteCardFieldsResult.MissingRequired
    }
    return BotQuoteCardFieldsResult.Ok(BotQuoteCardFields(chatId, quote, by))
}

/**
 * 与原处理器逐字一致的内容组装：`"> "` + quote + 空白 by 时无署名行，
 * 否则 `"\n— *" + by + "*"`。
 */
internal fun buildBotQuoteCardContent(quote: String, by: String): String {
    val attribution = if (by.isBlank()) "" else "\n— *$by*"
    return "> $quote$attribution"
}
