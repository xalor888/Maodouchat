package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `unpinChatMessage` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、
 * `sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、
 * `sendDivider`、`sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、`setMessageReaction`、
 * `starMessage`、`sendStatus`、`sendTable`、`sendAnimation`、`sendAudio`、
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`、
 * `sendChatAction`、`exportChatInviteLink`、`revokeChatInviteLink`、
 * `unpinAllChatMessages`、`setChatPhoto`、`deleteChatPhoto`、`pinChatMessage` 之后**第五十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/unpinChatMessage`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` / `messageId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()` /
 *   `obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，**都没有 `.trim()`**——
 *   全空白字段直接判空白，原处理器逐字如此（抽取顺序 chatId 先、messageId 后）；
 *   显式 JSON null 得字面量 `"null"`（`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，
 *   逐字语义）；
 * - **合并必填**：`chatId.isBlank() || messageId.isBlank()`→400
 *   `"chatId/messageId required"`，文案逐字（任一缺/空/纯空白即判缺）；
 * - 对象 / 数组型 `chatId` / `messageId` 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流、bot 可投递检查仍
 *   先于 body 解析（本轮重构保持它们在解析之前，等价），成员检查/chat 查询/
 *   `list` 预判/`toggle`/`logCommand`/WS 广播与响应仍在处理器，顺序与原处理器
 *   一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotUnpinChatMessageFields(
    val chatId: String,
    val messageId: String,
)

internal sealed interface BotUnpinChatMessageFieldsResult {
    data class Ok(val fields: BotUnpinChatMessageFields) : BotUnpinChatMessageFieldsResult
    data object MissingRequired : BotUnpinChatMessageFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId` + `messageId`（无缺省/夹界逻辑；任一缺/空/纯空白→MissingRequired）。
 */
internal fun parseBotUnpinChatMessageFields(obj: JsonObject): BotUnpinChatMessageFieldsResult {
    // 注意：chatId / messageId 均无 trim；缺/空/纯空白 → MissingRequired
    // （处理器侧 400 "chatId/messageId required"）。抽取顺序 chatId 先、messageId 后，与原处理器逐字一致。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || messageId.isBlank()) return BotUnpinChatMessageFieldsResult.MissingRequired
    return BotUnpinChatMessageFieldsResult.Ok(BotUnpinChatMessageFields(chatId, messageId))
}
