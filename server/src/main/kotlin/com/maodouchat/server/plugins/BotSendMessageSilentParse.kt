package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendMessageSilent` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation`、`sendAudio`、`editMessageCaption`、`sendTimeline`、`sendRemind`
 * 之后**第四十七块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendMessageSilent`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；显式 JSON null 得字面量 `"null"`
 *   （`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，逐字语义）；
 * - `text` 取 `obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)`：
 *   **没有 `.trim()`**（首尾空白原样进正文，逐字保留）；显式 JSON null 得字面量
 *   `"null"`（非空→`Ok`，特意钉住）；对象 / 数组型在 `?.jsonPrimitive` 处抛
 *   [IllegalArgumentException]（大声失败，路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；
 * - `parseMode` 取 `obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()`：
 *   **没有 `.trim()`**——`" md "`（带空格）大写后是 `" MD "`，不等于 `"MD"`，
 *   故落 `"TEXT"`，逐字语义；只有大写后**恰好** `"MARKDOWN"` / `"MD"` 才得
 *   `"MARKDOWN"`，其余（含键缺席、空串、显式 null 得字面量 `"NULL"`）一律 `"TEXT"`；
 * - **合并必填**（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，
 *   文案逐字）；
 * - 抽取顺序刻意与原处理器一致（`chatId`→`text`→`parseMode`，最后判必填）——
 *   坏类型字段的抛错顺序也因此不变；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——静默发送特性开关门控
 *   （`silent_send_disabled`）、成员检查、`serviceMessageRepo.insert`、
 *   `fanoutBotMessage`、`logCommand` 仍在处理器里，校验顺序与原处理器一致
 *   （开关门控→解析→必填→成员→落库→广播，逐行等价——下游一行不动）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendMessageSilentFields(
    val chatId: String,
    val text: String,
    val msgType: String,
)

internal sealed interface BotSendMessageSilentFieldsResult {
    data class Ok(val fields: BotSendMessageSilentFields) : BotSendMessageSilentFieldsResult
    data object MissingRequired : BotSendMessageSilentFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `text` → `parseMode`，最后判 `chatId.isBlank() || text.isBlank()`。
 */
internal fun parseBotSendMessageSilentFields(obj: JsonObject): BotSendMessageSilentFieldsResult {
    // 注意：chatId/text 无 trim；text 先取后截 4000；parseMode 无 trim 直接 uppercase，
    // 只有大写后恰好 "MARKDOWN"/"MD" 才得 "MARKDOWN"，其余一律 "TEXT"。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
    val parseMode = obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()
    val msgType = when {
        parseMode == "MARKDOWN" || parseMode == "MD" -> "MARKDOWN"
        else -> "TEXT"
    }
    if (chatId.isBlank() || text.isBlank()) return BotSendMessageSilentFieldsResult.MissingRequired
    return BotSendMessageSilentFieldsResult.Ok(BotSendMessageSilentFields(chatId, text, msgType))
}
