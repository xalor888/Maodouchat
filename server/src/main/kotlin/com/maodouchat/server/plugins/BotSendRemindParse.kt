package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendRemind` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation`、`sendAudio`、`editMessageCaption`、`sendTimeline` 之后**第四十六块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendRemind`
 * 处理器里内联的**抽取 / 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；显式 JSON null 得字面量 `"null"`
 *   （`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，逐字语义）；
 * - `text` 取 `(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)`：
 *   **双别名**（`text` → `message`，逐字顺序——只有 `text` 键缺席才回退到 `message`，
 *   `text` 显式 JSON null 得字面量 `"null"`、**不**回退，特意钉住）、**没有 `.trim()`**
 *   （首尾空白原样进正文，逐字保留）、**截 300**（先取后截）；
 *   对象 / 数组型 text 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - **合并必填**（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，
 *   文案逐字）；
 * - 抽取顺序刻意与原处理器一致（`chatId`→`text`，最后判必填）——
 *   坏类型字段的抛错顺序也因此不变；
 * - 内容组装逐字搬移：`"REMIND: " + text`（`type = "SYSTEM"`，原处理器逐字如此）；
 * - 注意：本端点**没有** `markdown_disabled` 特性开关门控（原处理器逐字如此，
 *   其余卡片端点有、这里没有——故意不补，零行为改动）；
 * - 纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——成员检查、
 *   `publishBotServiceMessage`、`logCommand` 仍在处理器里，校验顺序与原处理器一致。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendRemindFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendRemindFieldsResult {
    data class Ok(val fields: BotSendRemindFields) : BotSendRemindFieldsResult
    data object MissingRequired : BotSendRemindFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `text`（双别名 `text`→`message`），最后判 `chatId.isBlank() || text.isBlank()`。
 */
internal fun parseBotSendRemindFields(obj: JsonObject): BotSendRemindFieldsResult {
    // 注意：chatId 无 trim；text 双别名（text → message，只有 text 键缺席才回退、
    // 显式 null 得字面 "null" 不回退）、无 trim、先取后截 300。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)
    if (chatId.isBlank() || text.isBlank()) return BotSendRemindFieldsResult.MissingRequired
    return BotSendRemindFieldsResult.Ok(BotSendRemindFields(chatId, text))
}

/**
 * 与原处理器逐字一致的提醒正文组装：`"REMIND: " + text`。
 */
internal fun buildBotRemindContent(text: String): String =
    "REMIND: " + text
