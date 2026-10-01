package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendQuote` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown` 之后**第三十六块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendQuote`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotQuoteFields]，
 * 内容组装收敛为 [buildBotQuoteContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**——针对 `chatId`、`quote`、
 *   `note`（都是 `?.jsonPrimitive`：对象 / 数组型值在此抛；显式 JSON null
 *   本身就是 `JsonPrimitive` 的一种，`.content` 取到字面量 `"null"` 字符串、
 *   不抛——kotlinx-serialization-json 1.11.0 的 `JsonNull.content` 即 `"null"`，
 *   `sendMarkdown` 第三十五块 CI 已实证；路由层 `StatusPages` 把它映射为
 *   400「参数无效」，不是 500）；
 * - `quote` 取 `(obj["quote"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1500)`，
 *   注意三点：**存在性回退**（只有 `quote` 键完全缺席才看 `text`；`quote` 在但为
 *   显式 JSON null 时仍走 `quote` 分支，取到 `"null"` 字符串，不回退、不抛）；
 *   `take(1500)` 作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；
 *   字符串 `quote` 不带引号进模板（`?.jsonPrimitive?.content`，与 `sendJsonCard`
 *   的 `?.toString()` 怪语义**相反**，特意钉住）；
 * - `note` 取 `obj["note"]?.jsonPrimitive?.content.orEmpty().take(500)`——缺键得
 *   空串；显式 null 得 `"null"` 字符串（不抛、不参与必填）；对象 / 数组型 `note`
 *   在 `?.jsonPrimitive` 处抛（大声失败）；
 * - **双必填**（`chatId.isBlank() || quote.isBlank()`→400
 *   `"chatId/quote required"`，文案逐字）；判的是裁过 1500 的空白性，
 *   `note` 再长也不参与必填；
 * - 内容组装：`quote.lines().joinToString("\n") { "> " + it }` 逐行加引用前缀，
 *   `note` 非空（`isNotBlank`，注意判的是裁过 500 的串）时以 `"\n\n"` 拼接在后，
 *   否则只发引用块——组装逐字搬入 [buildBotQuoteContent]；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   特性开关门控（`isMarkdownEnabled`，拒绝文案 `"markdown disabled by admin"`）
 *   仍在处理器解析之前；纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotQuoteFields(
    val chatId: String,
    val quote: String,
    val note: String,
)

internal sealed interface BotQuoteFieldsResult {
    data class Ok(val fields: BotQuoteFields) : BotQuoteFieldsResult
    data object MissingRequired : BotQuoteFieldsResult
}

/**
 * `sendQuote` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `quote`（含 `quote`→`text` 的
 * 存在性回退）、再 `note`，然后判必填——对象 / 数组型值在 `?.jsonPrimitive`
 * 处抛 [IllegalArgumentException]（大声失败；显式 null 取到 `"null"` 字符串、
 * 不抛），`note` 缺键得空串（原处理器逐字如此，特意钉住）。
 */
internal fun parseBotQuoteFields(obj: JsonObject): BotQuoteFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val quote = (obj["quote"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1500)
    val note = obj["note"]?.jsonPrimitive?.content.orEmpty().take(500)
    // 注意：双必填——quote 判的是裁掉 1500 字符的空白性；note 不参与必填
    // （原处理器逐字如此）。
    if (chatId.isBlank() || quote.isBlank()) {
        return BotQuoteFieldsResult.MissingRequired
    }
    return BotQuoteFieldsResult.Ok(BotQuoteFields(chatId, quote, note))
}

/**
 * 与原处理器逐字一致的内容组装：逐行加 `"> "` 前缀；`note` 非空时以
 * `"\n\n"` 拼在引用块之后。
 */
internal fun buildBotQuoteContent(quote: String, note: String): String {
    val quoted = quote.lines().joinToString("\n") { "> " + it }
    return if (note.isNotBlank()) quoted + "\n\n" + note else quoted
}
