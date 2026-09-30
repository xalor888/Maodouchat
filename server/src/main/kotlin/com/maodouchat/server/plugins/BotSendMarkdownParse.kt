package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendMarkdown` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard` 之后**第三十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendMarkdown`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotMarkdownFields]，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**——针对 `chatId` 与 `text`
 *   （都是 `?.jsonPrimitive`：对象 / 数组 / 显式 null 型值在 `.content` 处抛；
 *   路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `text` 取 `(obj["text"] ?: obj["markdown"])?.jsonPrimitive?.content.orEmpty().take(4000)`，
 *   注意三点：**存在性回退**（只有 `text` 键完全缺席才看 `markdown`；`text` 在但为
 *   显式 JSON null 时仍走 `text` 分支，在 `.content` 处抛，不回退）；
 *   `take(4000)` 作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；
 *   字符串 `text` 不带引号进模板（`?.jsonPrimitive?.content`，与 `sendJsonCard`
 *   的 `?.toString()` 怪语义**相反**，特意钉住）；
 * - `silentRequested` 取 `obj["silent"]?.jsonPrimitive?.booleanOrNull == true`——
 *   缺键 / JSON null 得 `false`；字符串 `"true"`/`"false"` 按布尔语义解析
 *   （`booleanOrNull` 认 `"true"`）；对象 / 数组型 `silent` 在 `?.jsonPrimitive`
 *   处抛（大声失败）；真正的 `silent`（`silentRequested && isSilentSendEnabled()`）
 *   仍在处理器里组装——配置读取不进纯函数；
 * - **双必填**（`chatId.isBlank() || text.isBlank()`→400
 *   `"chatId/text required"`，文案逐字）；判的是裁过 4000 的空白性。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotMarkdownFields(
    val chatId: String,
    val text: String,
    /** 原始 `silent` 请求值；是否真正静默发送仍由处理器结合运行时配置决定。 */
    val silentRequested: Boolean,
)

internal sealed interface BotMarkdownFieldsResult {
    data class Ok(val fields: BotMarkdownFields) : BotMarkdownFieldsResult
    data object MissingRequired : BotMarkdownFieldsResult
}

/**
 * `sendMarkdown` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `text`（含 `text`→`markdown` 的
 * 存在性回退）、再 `silentRequested`，然后判必填——`chatId`/`text` 的
 * `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]（大声失败），
 * `silent` 的 `?.jsonPrimitive?.booleanOrNull == true` 缺键得 `false`
 * （原处理器逐字如此，特意钉住）。
 */
internal fun parseBotMarkdownFields(obj: JsonObject): BotMarkdownFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = (obj["text"] ?: obj["markdown"])?.jsonPrimitive?.content.orEmpty().take(4000)
    val silentRequested = obj["silent"]?.jsonPrimitive?.booleanOrNull == true
    // 注意：双必填——text 判的是裁掉 4000 字符的空白性（原处理器逐字如此）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotMarkdownFieldsResult.MissingRequired
    }
    return BotMarkdownFieldsResult.Ok(BotMarkdownFields(chatId, text, silentRequested))
}
