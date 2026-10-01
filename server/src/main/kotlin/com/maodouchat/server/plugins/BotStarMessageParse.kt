package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `starMessage` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、
 * `setMessageReaction` 之后**第三十九块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `starMessage`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotStarMessageFields]，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `messageId` 取 `obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，
 *   **没有 `.trim()`**——全空白（含两端空白）messageId 直接判空白，
 *   原处理器逐字如此（`" m1 "` 原样进必填判断、进下游，非裁剪后）；
 * - **已知字段类型错时抛 [IllegalArgumentException]**——`?.jsonPrimitive`：
 *   对象 / 数组型值在此抛；显式 JSON null 本身就是 `JsonPrimitive` 的一种，
 *   `.content` 取到字面量 `"null"` 字符串、不抛（kotlinx-serialization-json
 *   1.11.0 的 `JsonNull.content` 即 `"null"`，`sendMarkdown` 第三十五块 CI
 *   已实证；路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - **单必填**（`messageId.isBlank()`→400 `"messageId required"`，文案逐字），
 *   判的是未裁剪串的空白性；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 单必填（纯函数）→ 消息存在性
 *   （处理器）→ 成员检查（处理器）），特性开关门控
 *   （`isMessageStarringEnabled`，拒绝文案 `"starring_disabled"`）
 *   仍在处理器解析之前；纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotStarMessageFields(
    val messageId: String,
)

internal sealed interface BotStarMessageFieldsResult {
    data class Ok(val fields: BotStarMessageFields) : BotStarMessageFieldsResult
    data object MissingRequired : BotStarMessageFieldsResult
}

/**
 * `starMessage` 的请求体解析。
 *
 * 抽取表达式与原处理器逐字一致：`obj["messageId"]?.jsonPrimitive?.content.orEmpty()`
 * （无 `trim()`）；单必填判 `isBlank()`——对象 / 数组型值在 `?.jsonPrimitive`
 * 处抛 [IllegalArgumentException]（大声失败；显式 null 取到 `"null"` 字符串、
 * 不抛），字段缺键得空串（原处理器逐字如此，特意钉住）。
 */
internal fun parseBotStarMessageFields(obj: JsonObject): BotStarMessageFieldsResult {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：无 trim()——判的是原串的空白性（" m1 " 原样通过必填检查进下游）。
    if (messageId.isBlank()) {
        return BotStarMessageFieldsResult.MissingRequired
    }
    return BotStarMessageFieldsResult.Ok(BotStarMessageFields(messageId))
}
