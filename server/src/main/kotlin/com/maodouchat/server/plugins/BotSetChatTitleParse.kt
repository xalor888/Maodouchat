package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setChatTitle` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、
 * `sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、
 * `sendDivider`、`sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`/`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、`setMessageReaction`、
 * `starMessage`、`sendStatus`、`sendTable`、`sendAnimation`、`sendAudio`、
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`、
 * `sendChatAction`、`exportChatInviteLink`、`revokeChatInviteLink`、
 * `unpinAllChatMessages`、`setChatPhoto`、`deleteChatPhoto`、`pinChatMessage`、
 * `unpinChatMessage`、`setMyName` 之后**第五十七块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setChatTitle`
 * 处理器里内联的**抽取 / 归一化 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空白，原处理器逐字如此（抽取顺序 chatId 先、title 后）；
 * - `title` 取 `(obj["title"] ?: obj["groupName"])?.jsonPrimitive?.content.orEmpty().trim()`——
 *   `title` 优先、`groupName` 回退；**显式 JSON null 不触发回退**（`JsonNull` 是非空实例，
 *   `?:` 不生效→得字面量 `"null"`，`.trim()` 后仍为 `"null"`，非空→`Ok`，逐字语义）；
 * - **合并必填 + 长度夹界**：`chatId.isBlank() || title.isBlank() || title.length > 50`→400
 *   `"chatId/title required (1-50)"`，文案逐字（长度上限判的是 **trim 之后** 的 title，
 *   夹界在 trim 之后，原处理器逐字如此）；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流在原处理器里**先于** body 解析，
 *   本轮保持它在解析之前，等价；成员检查/`updateName`/`logCommand`/修订通知与响应
 *   仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetChatTitleFields(
    val chatId: String,
    val title: String,
)

internal sealed interface BotSetChatTitleFieldsResult {
    data class Ok(val fields: BotSetChatTitleFields) : BotSetChatTitleFieldsResult
    /** 400 `"chatId/title required (1-50)"` 的全部前件：chatId 缺/空/纯空白，或 title 缺/空/纯空白，或 trim 后 title 超 50 字符。 */
    data object Invalid : BotSetChatTitleFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 归一化 + 合并校验。
 * 双字段端点：`chatId`（无 trim）+ `title`（`title` 优先、`groupName` 回退、trim 在先、长度夹界在后）。
 */
internal fun parseBotSetChatTitleFields(obj: JsonObject): BotSetChatTitleFieldsResult {
    // 注意：chatId 无 trim；title 优先于 groupName；任一键显式 null → 字面量 "null"（不回退、不判空）。
    // trim 在长度校验之前；任一缺/空/纯空白或 title 超 50 → Invalid（处理器侧 400 "chatId/title required (1-50)"）。
    // 抽取顺序 chatId 先、title 后，与原处理器逐字一致。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val title = (obj["title"] ?: obj["groupName"])?.jsonPrimitive?.content.orEmpty().trim()
    if (chatId.isBlank() || title.isBlank() || title.length > 50) return BotSetChatTitleFieldsResult.Invalid
    return BotSetChatTitleFieldsResult.Ok(BotSetChatTitleFields(chatId, title))
}
