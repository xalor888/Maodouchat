package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendJsonCard` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`
 * 之后**第三十四块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendJsonCard`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotJsonCardFields]，
 * 内容组装收敛为 [buildBotJsonCardContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**——只针对 `chatId`
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `payload` 取 `(obj["json"] ?: obj["data"])?.toString()?.take(500).orEmpty()`，
 *   注意它是 `?.toString()` **不是** `?.jsonPrimitive`：对象 / 数组型
 *   `json`/`data` **不抛错**，原样序列化进模板（字符串带引号，
 *   如 `"abc"` 模板里呈现为带引号的 `"abc"`）；
 * - **存在性回退**：只有 `json` 键完全缺席时才看 `data`；`json` 在但为显式
 *   JSON null 时仍走 `json` 分支得字面 `"null"`（`JsonNull.toString()` 即
 *   `"null"`，**不**被 `orEmpty` 吞掉），特意钉住；
 * - `take(500)` 作用于 `toString()` **之后**（整段序列化上限，不是原始字段上限）；
 * - **双必填**（`chatId.isBlank() || payload.isBlank()`→400
 *   `"chatId/json required"`，文案逐字）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotJsonCardFields(
    val chatId: String,
    val payload: String,
)

internal sealed interface BotJsonCardFieldsResult {
    data class Ok(val fields: BotJsonCardFields) : BotJsonCardFieldsResult
    data object MissingRequired : BotJsonCardFieldsResult
}

/**
 * `sendJsonCard` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `payload`（含 `json`→`data` 的
 * 存在性回退），然后判必填——`chatId` 的 `?.jsonPrimitive` 在类型错时抛
 * [IllegalArgumentException]（大声失败），`payload` 的 `?.toString()` 则不抛
 * （原处理器逐字如此，特意钉住）。
 */
internal fun parseBotJsonCardFields(obj: JsonObject): BotJsonCardFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val payload = (obj["json"] ?: obj["data"])?.toString()?.take(500).orEmpty()
    // 注意：双必填——payload 判的是序列化后裁掉 500 字符的空白性（原处理器逐字如此）。
    if (chatId.isBlank() || payload.isBlank()) {
        return BotJsonCardFieldsResult.MissingRequired
    }
    return BotJsonCardFieldsResult.Ok(BotJsonCardFields(chatId, payload))
}

/**
 * 与原处理器逐字一致的内容组装：`"```json\n" + payload + "\n```"`。
 */
internal fun buildBotJsonCardContent(payload: String): String =
    "```json\n" + payload + "\n```"
