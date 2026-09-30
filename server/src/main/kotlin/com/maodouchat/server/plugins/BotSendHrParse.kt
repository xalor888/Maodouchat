package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendHr` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast` 之后**第二十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationWidgetsRoutes` 的 `/api/bot/sendHr` 处理器里的**抽取 /
 * 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `note`（键名 `text`）取 `.orEmpty().take(200)`——缺省回 `""`（**无默认值**，
 *   与 `sendBadge` 的 `label` 不同，特意钉住），显式 JSON null 得字面 `"null"`；
 *   **不 trim**（原处理器逐字如此，前导空格计入 200 上限），超长截 200；
 *   对象/数组型 `text` 的 `?.jsonPrimitive` 抛 `IllegalArgumentException`（大声失败）；
 * - 单必填（`chatId.isBlank()`→400）；`text` 非必填；
 * - 校验顺序刻意与原处理器一致（必填（纯函数）→ 成员检查（处理器）），
 *   markdown 总开关检查仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：`note` 非空白时 `"---\n$note\n---"`，空白时 `"---"`
 *   （`type = "MARKDOWN"`，原处理器逐字如此）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendHrFields(
    val chatId: String,
    val note: String,
)

internal sealed interface BotSendHrFieldsResult {
    data class Ok(val fields: BotSendHrFields) : BotSendHrFieldsResult
    data object MissingRequired : BotSendHrFieldsResult
}

internal fun parseBotSendHrFields(obj: JsonObject): BotSendHrFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：note 无默认值（缺省回 ""），不 trim，前导空格计入 200 上限；
    // 对象/数组型 text 的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    val note = obj["text"]?.jsonPrimitive?.content.orEmpty().take(200)
    if (chatId.isBlank()) {
        return BotSendHrFieldsResult.MissingRequired
    }
    return BotSendHrFieldsResult.Ok(BotSendHrFields(chatId, note))
}

/**
 * 与原处理器逐字一致的分隔线消息内容组装：
 * `note` 非空白时 `"---\n$note\n---"`，空白时 `"---"`。
 */
internal fun buildBotHrContent(note: String): String =
    if (note.isNotBlank()) "---\n$note\n---" else "---"
