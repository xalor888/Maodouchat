package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendToast` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`
 * 之后**第二十四块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationCardsRoutes` 的 `/api/bot/sendToast` 处理器里的**抽取 /
 * 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `text` 取 `(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(200)`——
 *   `text`→`message` 别名链是 `?:` 接**存在性**：`text` 键缺席才穿透到 `message`；
 *   显式 JSON null 的 `text` 得字面 `"null"`（`JsonNull` 本身是 `JsonPrimitive`，
 *   `.content` 为 `"null"`），**不**穿透到 `message`（特意钉住）；
 *   **不 trim**（原处理器逐字如此，前导空格计入 200 上限）；截断 200 逐字不变；
 * - 双必填（`chatId.isBlank() || text.isBlank()`→400 `"chatId/text required"`）——
 *   `text` 缺省/空白**判缺**（与 `sendAlert` 的 `text` 不同，这里没有回退默认值，
 *   原处理器逐字如此，特意钉住）；
 * - 校验顺序刻意与原处理器一致（必填（纯函数）→ 成员检查（处理器）），
 *   本端点无 markdown 总开关；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：`"TOAST: $text"`（`type = "SYSTEM"`，原处理器逐字如此）；
 * - 响应体 `put("type", "SYSTEM")` 等逐字不变，下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendToastFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendToastFieldsResult {
    data class Ok(val fields: BotSendToastFields) : BotSendToastFieldsResult
    data object MissingRequired : BotSendToastFieldsResult
}

internal fun parseBotSendToastFields(obj: JsonObject): BotSendToastFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：别名链是 ?: 接存在性——text 键缺席才穿透到 message；
    // 显式 JSON null 的 text 得字面 "null"（非空白），不穿透、不判缺；
    // 不 trim，前导空格计入 200 上限；take(200) 逐字不变（原处理器逐字顺序）。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(200)
    // 注意：双必填——text 缺省/空白判缺，没有回退默认值（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendToastFieldsResult.MissingRequired
    }
    return BotSendToastFieldsResult.Ok(BotSendToastFields(chatId, text))
}

/**
 * 与原处理器逐字一致的轻提示消息内容组装：`"TOAST: $text"`。
 */
internal fun buildBotToastContent(text: String): String =
    "TOAST: $text"
