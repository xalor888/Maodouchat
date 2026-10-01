package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendStatus` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、
 * `setMessageReaction`、`starMessage` 之后**第四十块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendStatus`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotSendStatusFields]，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，
 *   **没有 `.trim()`**——全空白（含两端空白）chatId 直接判空白，
 *   原处理器逐字如此；
 * - `text` 取 `(obj["text"] ?: obj["status"])?.jsonPrimitive?.content.orEmpty().take(200)`：
 *   **先 200 字符截断、后判空白**——前 200 字符全空白即判缺失
 *   （第 201 个字符之后的内容进不了判据），`text` 缺键才回退 `status`，
 *   **显式 JSON null 不回退**（`?:` 判的是 Kotlin null，不是 `JsonNull`；
 *   `JsonNull` 取到字面量 `"null"` 字符串、不抛），同样无 `trim()`；
 * - **合并必填**（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`，
 *   文案逐字），判的是未裁剪串的空白性；
 * - **已知字段类型错时抛 [IllegalArgumentException]**——`?.jsonPrimitive`：
 *   对象 / 数组型值在此抛；显式 JSON null 本身就是 `JsonPrimitive` 的一种，
 *   `.content` 取到字面量 `"null"` 字符串、不抛（kotlinx-serialization-json
 *   1.11.0 的 `JsonNull.content` 即 `"null"`，`sendMarkdown` 第三十五块 CI
 *   已实证；路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 合并必填（纯函数）→
 *   成员检查（处理器）），纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendStatusFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendStatusFieldsResult {
    data class Ok(val fields: BotSendStatusFields) : BotSendStatusFieldsResult
    data object MissingRequired : BotSendStatusFieldsResult
}

/**
 * `sendStatus` 的请求体解析。
 *
 * 抽取表达式与原处理器逐字一致：`obj["chatId"]?.jsonPrimitive?.content.orEmpty()`
 * （无 `trim()`）；`text` 为 `(obj["text"] ?: obj["status"])?.jsonPrimitive?.content.orEmpty().take(200)`
 * （无 `trim()`；先截断后判空白；`text` 缺键才回退 `status`，显式 JSON null
 * 不回退、得字面量 `"null"`）；合并必填判 `chatId.isBlank() || text.isBlank()`——
 * 对象 / 数组型值在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 * （大声失败；显式 null 取到 `"null"` 字符串、不抛），字段缺键得空串
 * （原处理器逐字如此，特意钉住）。
 */
internal fun parseBotSendStatusFields(obj: JsonObject): BotSendStatusFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：无 trim()；text 先 take(200) 再判空白；text 缺键才回退 status。
    val text = (obj["text"] ?: obj["status"])?.jsonPrimitive?.content.orEmpty().take(200)
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendStatusFieldsResult.MissingRequired
    }
    return BotSendStatusFieldsResult.Ok(BotSendStatusFields(chatId, text))
}
