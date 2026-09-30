package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendAlert` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist` 之后**第二十块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationStatusRoutes` 的 `/api/bot/sendAlert` 处理器里的**抽取 /
 * 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `text` 用 `(obj["text"] ?: obj["message"])` 别名链——`?:` 接在存在性上：
 *   显式 JSON null 是 `JsonPrimitive`、`.content` 为 `"null"` 字符串，**不穿透**
 *   到 `message`（与 `sendPollQuiz` 的 question 别名链同一族语义，特意钉住）；
 * - `text` 取 `.orEmpty().take(300)` **不 trim**（原处理器逐字如此）；
 * - `level` 取 `.orEmpty().ifBlank { "info" }.take(16)`——缺省/空白回 `"info"`，
 *   但显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发，特意钉住）；
 *   截断发生在默认值之后（原处理器逐字顺序）；
 * - 双必填（`chatId.isBlank() || text.isBlank()`→400）；`level` 非必填；
 * - 校验顺序刻意与原处理器一致（必填（纯函数）→ 成员检查（处理器）），
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：`"ALERT[$level]: $text"`（`type = "SYSTEM"`，
 *   原处理器逐字如此）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendAlertFields(
    val chatId: String,
    val text: String,
    val level: String,
)

internal sealed interface BotSendAlertFieldsResult {
    data class Ok(val fields: BotSendAlertFields) : BotSendAlertFieldsResult
    data object MissingRequired : BotSendAlertFieldsResult
}

internal fun parseBotSendAlertFields(obj: JsonObject): BotSendAlertFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：text 别名链接在存在性上——显式 JSON null 不穿透到 message，
    // 得字面 "null"（原处理器逐字语义）；不 trim，take(300)。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)
    // 注意：ifBlank 在 take 之前——缺省/空白回 "info"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；超长 level 先回退默认再截 16（原处理器逐字顺序）。
    val level = obj["level"]?.jsonPrimitive?.content.orEmpty().ifBlank { "info" }.take(16)
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendAlertFieldsResult.MissingRequired
    }
    return BotSendAlertFieldsResult.Ok(BotSendAlertFields(chatId, text, level))
}

/**
 * 与原处理器逐字一致的告警消息内容组装：`"ALERT[$level]: $text"`。
 */
internal fun buildBotAlertContent(text: String, level: String): String =
    "ALERT[$level]: $text"
