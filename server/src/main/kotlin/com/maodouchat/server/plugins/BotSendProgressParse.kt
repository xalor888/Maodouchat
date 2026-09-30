package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendProgress` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider` 之后**第二十七块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationStatusRoutes` 的 `/api/bot/sendProgress` 处理器里的**抽取 /
 * 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `title` 取 `.orEmpty().ifBlank { "Progress" }.take(40)`——缺省/空白回 `"Progress"`，
 *   显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发，特意钉住）；
 *   **不 trim**（原处理器逐字如此，前导空格计入 40 上限）；
 *   截断发生在默认值之后（原处理器逐字顺序）；
 * - `percent` 取 `(obj["percent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 100)`——
 *   注意它是**字符串解析**：JSON 数字 `75` 经 `.content` 得 `"75"` 照样解析；
 *   缺省 / 显式 null（`.content` 为 `"null"`）/ 非数字串 / 小数串（`"50.5"` 的
 *   `toIntOrNull()` 为 null）一律回 `0`；负数钳到 `0`、超 `100` 钳到 `100`
 *   （原处理器逐字如此，特意钉住）；
 * - 单必填（`chatId.isBlank()`→400）；`title`/`percent` 非必填；
 * - 校验顺序刻意与原处理器一致（必填（纯函数）→ 成员检查（处理器）），
 *   markdown 总开关检查仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：进度条 `filled = percent / 10`（整数除法），
 *   `"**$title**\n`[$bar]` $percent%"`（`type = "MARKDOWN"`，原处理器逐字如此）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendProgressFields(
    val chatId: String,
    val title: String,
    val percent: Int,
)

internal sealed interface BotSendProgressFieldsResult {
    data class Ok(val fields: BotSendProgressFields) : BotSendProgressFieldsResult
    data object MissingRequired : BotSendProgressFieldsResult
}

internal fun parseBotSendProgressFields(obj: JsonObject): BotSendProgressFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：ifBlank 在 take 之前——缺省/空白回 "Progress"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；不 trim，前导空格计入 40 上限（原处理器逐字顺序）。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Progress" }.take(40)
    // 注意：字符串解析 + 钳制——缺省/显式 null/非数字/小数一律回 0，再 coerceIn(0, 100)
    //（原处理器逐字如此）。
    val percent = (obj["percent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 100)
    if (chatId.isBlank()) {
        return BotSendProgressFieldsResult.MissingRequired
    }
    return BotSendProgressFieldsResult.Ok(BotSendProgressFields(chatId, title, percent))
}

/**
 * 与原处理器逐字一致的进度条消息内容组装。
 *
 * 前置条件：`percent` 须已钳制在 0..100（[parseBotSendProgressFields] 保证）——
 * `filled = percent / 10` 为整数除法，`bar` 为 10 格 `"#" + "-"`。
 */
internal fun buildBotProgressContent(title: String, percent: Int): String {
    val filled = percent / 10
    // 注：刻意把 repeat 提到模板外，避免模板内嵌套引号（Kotlin 2.4.0 K2 下报 Syntax error）。
    val hashes = "#".repeat(filled)
    val dashes = "-".repeat(10 - filled)
    val bar = hashes + dashes
    return "**$title**\n`[$bar]` $percent%"
}
