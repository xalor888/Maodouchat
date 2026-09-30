package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendBanner` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard` 之后**第三十三块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendBanner`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotBannerFields]，
 * 内容组装收敛为 [buildBotBannerContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `title` 取 `obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Banner" }.take(40)`——
 *   默认文案接的是**空白性**而非存在性：键缺席**与**键在但空白都得 `"Banner"`
 *   （`"null"` 字面与其它非空白串不触发，**不** trim；先 `ifBlank` 再 `take(40)`，
 *   原处理器逐字如此，特意钉住）；
 * - `text` 取 `(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(240)`——
 *   **存在性回退**：只有 `text` 键完全缺席时才看 `message`；`text` 在但为显式
 *   JSON null 时仍走 `text` 分支得字面 `"null"`（`JsonNull` 本身是 `JsonPrimitive`，
 *   `.content` 为 `"null"`，**不**被 `orEmpty` 吞掉），特意钉住；
 * - **双必填**（`chatId.isBlank() || text.isBlank()`→400
 *   `"chatId/text required"`）；`title` 非必填（缺省/空白回 `"Banner"`，原处理器
 *   逐字如此，特意钉住）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotBannerFields(
    val chatId: String,
    val title: String,
    val text: String,
)

internal sealed interface BotBannerFieldsResult {
    data class Ok(val fields: BotBannerFields) : BotBannerFieldsResult
    data object MissingRequired : BotBannerFieldsResult
}

/**
 * `sendBanner` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `title`、最后 `text`（含 `text`→
 * `message` 的存在性回退），然后判必填——坏类型字段的抛错顺序也因此不变
 * （对象/数组型已知字段的 `?.jsonPrimitive` 抛 [IllegalArgumentException]，
 * 大声失败）。
 */
internal fun parseBotBannerFields(obj: JsonObject): BotBannerFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Banner" }.take(40)
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(240)
    // 注意：双必填——title 缺省/空白不判缺（回 "Banner"，原处理器逐字如此）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotBannerFieldsResult.MissingRequired
    }
    return BotBannerFieldsResult.Ok(BotBannerFields(chatId, title, text))
}

/**
 * 与原处理器逐字一致的内容组装：`"## " + title + "\n" + text`。
 */
internal fun buildBotBannerContent(title: String, text: String): String =
    "## " + title + "\n" + text
