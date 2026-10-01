package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendChatAction` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、
 * `sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、
 * `sendDivider`、`sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、`setMessageReaction`、
 * `starMessage`、`sendStatus`、`sendTable`、`sendAnimation`、`sendAudio`、
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`
 * 之后**第四十八块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendChatAction`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；显式 JSON null 得字面量 `"null"`
 *   （`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，逐字语义）；
 * - `action` 取 `obj["action"]?.jsonPrimitive?.content.orEmpty().lowercase().ifBlank { "typing" }`：
 *   **先 `.lowercase()` 后判空**——显式 JSON null 得字面量 `"null"`，`lowercase()` 后
 *   是 `"null"`（非空，不回退 `"typing"`，逐字怪语义，特意钉住）；缺席/空串/
 *   纯空白→`""`→`ifBlank` 回退 `"typing"`；对象 / 数组型在 `?.jsonPrimitive` 处抛
 *   [IllegalArgumentException]（大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - **合并必填**只有 `chatId`（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）——
 *   `action` 恒有默认值，从不判空（逐字语义）；
 * - 抽取顺序刻意与原处理器一致（`chatId`→`action`，最后判必填）——
 *   坏类型字段的抛错顺序也因此不变；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流门控、成员检查、
 *   `isTyping` 推导、typing 侧信道 fanout（9.124 拉黑过滤）、`logCommand` 与
 *   `{"ok": true, "action": …}` 响应仍在处理器里，顺序与原处理器一致，
 *   逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendChatActionFields(
    val chatId: String,
    val action: String,
)

internal sealed interface BotSendChatActionFieldsResult {
    data class Ok(val fields: BotSendChatActionFields) : BotSendChatActionFieldsResult
    data object MissingRequired : BotSendChatActionFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `action`（先 lowercase 后 ifBlank 回退），最后判 `chatId.isBlank()`。
 */
internal fun parseBotSendChatActionFields(obj: JsonObject): BotSendChatActionFieldsResult {
    // 注意：chatId 无 trim；action 先 lowercase 后判空，缺席/空/纯空白→"typing"、
    // 显式 null 得字面 "null"（非空→不回退，逐字语义）；只有 chatId 参与必填。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val action = obj["action"]?.jsonPrimitive?.content.orEmpty().lowercase().ifBlank { "typing" }
    if (chatId.isBlank()) return BotSendChatActionFieldsResult.MissingRequired
    return BotSendChatActionFieldsResult.Ok(BotSendChatActionFields(chatId, action))
}
