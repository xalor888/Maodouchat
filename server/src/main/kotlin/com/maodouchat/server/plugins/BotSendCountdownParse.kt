package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendCountdown` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert` 之后**第二十一块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationStatusRoutes` 的 `/api/bot/sendCountdown` 处理器里的**抽取 /
 * 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `title` 取 `.orEmpty().ifBlank { "Countdown" }.take(40)`——缺省/空白回 `"Countdown"`，
 *   但显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发，特意钉住）；
 *   **不 trim**（原处理器逐字如此，前导空格计入 40 上限）；
 *   截断发生在默认值之后（原处理器逐字顺序）；
 * - `seconds` 取 `(obj["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60).coerceIn(5, 86400)`——
 *   非整数字符串（如 `"abc"`、浮点 `"30.5"`）`toIntOrNull()` 回 `null`→**回退 60**，
 *   不报错（特意钉住）；显式 JSON null 得字面 `"null"`→`toIntOrNull()` 回 `null`→60；
 *   负数/超界被 `coerceIn` 夹回 `[5, 86400]`（5 与 86400 本身保留）；
 *   对象/数组型 `seconds` 的 `?.jsonPrimitive` 抛 `IllegalArgumentException`（大声失败）；
 * - 单必填（`chatId.isBlank()`→400）；`title`/`seconds` 非必填；
 * - 校验顺序刻意与原处理器一致（必填（纯函数）→ 成员检查（处理器）），
 *   markdown 总开关检查仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：`"**$title**\n`T-${seconds}s`"`（`type = "MARKDOWN"`，
 *   原处理器逐字如此）；
 * - 响应体回显 `seconds` 值与原处理器逐字一致（抽取后的钳制值）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendCountdownFields(
    val chatId: String,
    val title: String,
    val seconds: Int,
)

internal sealed interface BotSendCountdownFieldsResult {
    data class Ok(val fields: BotSendCountdownFields) : BotSendCountdownFieldsResult
    data object MissingRequired : BotSendCountdownFieldsResult
}

internal fun parseBotSendCountdownFields(obj: JsonObject): BotSendCountdownFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：ifBlank 在 take 之前——缺省/空白回 "Countdown"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；不 trim，前导空格计入 40 上限（原处理器逐字顺序）。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Countdown" }.take(40)
    // 注意：toIntOrNull() 失败→回退 60（不报错，特意钉住）；
    // 对象/数组型 seconds 的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    val seconds = (obj["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60).coerceIn(5, 86400)
    if (chatId.isBlank()) {
        return BotSendCountdownFieldsResult.MissingRequired
    }
    return BotSendCountdownFieldsResult.Ok(BotSendCountdownFields(chatId, title, seconds))
}

/**
 * 与原处理器逐字一致的倒计时消息内容组装：`"**$title**\n`T-${seconds}s`"`。
 */
internal fun buildBotCountdownContent(title: String, seconds: Int): String =
    "**$title**\n`T-${seconds}s`"
