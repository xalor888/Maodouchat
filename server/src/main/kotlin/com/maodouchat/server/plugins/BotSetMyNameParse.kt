package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setMyName` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
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
 * `unpinAllChatMessages`、`setChatPhoto`、`deleteChatPhoto`、`pinChatMessage`、
 * `unpinChatMessage` 之后**第五十六块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setMyName`
 * 处理器里内联的**抽取 / 归一化 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - 键优先级：`obj["name"] ?: obj["displayName"]`——`name` 优先；**显式 JSON null
 *   不触发回退**（`JsonNull` 是非空实例，`?:` 不生效，此时得字面量 `"null"`）；
 * - `?.jsonPrimitive?.content.orEmpty()` 后先 `.trim()` 再 `.take(120)`——
 *   全空白直接判空（trim 先行，逐字语义）；超长截断 120 字符；
 * - 显式 JSON null 得字面量 `"null"`（`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，
 *   逐字语义；注意此时 `?:` 回退已失效）；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流仍先于 body 解析
 *   （本轮重构保持它在解析之前，等价），`setMyName`/`logCommand`/响应仍在处理器，
 *   顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetMyNameFields(
    val name: String,
)

internal sealed interface BotSetMyNameFieldsResult {
    data class Ok(val fields: BotSetMyNameFields) : BotSetMyNameFieldsResult
    /** 400 `"invalid name"` 的全部前件：缺 / 空 / 纯空白（trim 之后）。 */
    data object InvalidName : BotSetMyNameFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 归一化 + 必填校验。
 * 单字段端点：`name` 优先、`displayName` 回退（显式 null 不回退→`"null"` 怪语义）；
 * trim 后判空，超长 `.take(120)`。
 */
internal fun parseBotSetMyNameFields(obj: JsonObject): BotSetMyNameFieldsResult {
    // 注意：name 优先于 displayName；任一键显式 null → 字面量 "null"（不回退）。
    // trim 在 take(120) 之前；缺 / 空 / 纯空白 → InvalidName（处理器侧 400 "invalid name"）。
    val name = (obj["name"] ?: obj["displayName"])?.jsonPrimitive?.content.orEmpty().trim().take(120)
    if (name.isBlank()) return BotSetMyNameFieldsResult.InvalidName
    return BotSetMyNameFieldsResult.Ok(BotSetMyNameFields(name))
}
