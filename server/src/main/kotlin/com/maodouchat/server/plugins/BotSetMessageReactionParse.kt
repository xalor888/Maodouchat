package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setMessageReaction` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode` 之后**第三十八块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `setMessageReaction`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotReactionFields]，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**——针对 `messageId`、
 *   `emoji`（都是 `?.jsonPrimitive`：对象 / 数组型值在此抛；显式 JSON null
 *   本身就是 `JsonPrimitive` 的一种，`.content` 取到字面量 `"null"` 字符串、
 *   不抛——kotlinx-serialization-json 1.11.0 的 `JsonNull.content` 即 `"null"`，
 *   `sendMarkdown` 第三十五块 CI 已实证；路由层 `StatusPages` 把它映射为
 *   400「参数无效」，不是 500）；
 * - `emoji` 取 `obj["emoji"]?.jsonPrimitive?.content.orEmpty().trim()`——
 *   `trim()` 作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；
 *   双必填与白名单判的都是**裁过两端空白**的串：`" 👍 "` 合法通过，
 *   全空白 emoji 得 `MissingRequired`（不是 `UnsupportedEmoji`）；
 * - 显式 null 的 emoji 得字面量 `"null"`（不抛、经 `trim()` 仍为 `"null"`），
 *   它**不在** [ALLOWED_REACTION_EMOJIS] 里 → `UnsupportedEmoji`，特意钉住；
 * - **双必填**（`messageId.isBlank() || emoji.isBlank()`→400
 *   `"messageId/emoji required"`，文案逐字）先于白名单判断——
 *   白名单拒绝（400 `"unsupported emoji"`，文案逐字）只在双必填通过后发生；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 双必填（纯函数）→ 白名单
 *   （纯函数）→ 消息存在性（处理器）→ 成员检查（处理器）），
 *   特性开关门控（`isReactionsEnabled`，拒绝文案 `"reactions_disabled"`）
 *   仍在处理器解析之前；纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotReactionFields(
    val messageId: String,
    val emoji: String,
)

internal sealed interface BotReactionFieldsResult {
    data class Ok(val fields: BotReactionFields) : BotReactionFieldsResult
    data object MissingRequired : BotReactionFieldsResult
    data object UnsupportedEmoji : BotReactionFieldsResult
}

/**
 * `setMessageReaction` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `messageId`、再 `emoji`（含 `trim()`），
 * 然后判双必填、再判白名单——对象 / 数组型值在 `?.jsonPrimitive`
 * 处抛 [IllegalArgumentException]（大声失败；显式 null 取到 `"null"` 字符串、
 * 不抛），字段缺键得空串（原处理器逐字如此，特意钉住）。
 */
internal fun parseBotReactionFields(obj: JsonObject): BotReactionFieldsResult {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    val emoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().trim()
    // 注意：双必填先于白名单——判的是 trim() 之后的空白性。
    if (messageId.isBlank() || emoji.isBlank()) {
        return BotReactionFieldsResult.MissingRequired
    }
    if (emoji !in ALLOWED_REACTION_EMOJIS) {
        return BotReactionFieldsResult.UnsupportedEmoji
    }
    return BotReactionFieldsResult.Ok(BotReactionFields(messageId, emoji))
}
