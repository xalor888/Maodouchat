package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `editMessageCaption` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation`、`sendAudio` 之后**第四十四块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/editMessageCaption`
 * 处理器里内联的**抽取 / 校验 / 对端 E2EE 拒绝判定 / 内容组装**逻辑收敛为纯函数，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `messageId` 取 `obj["messageId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；空缺 → [MissingMessageId]（处理器回 400
 *   `"messageId required"`，文案逐字）；
 * - `caption` 取 `(obj["caption"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1000)`：
 *   **双别名**（`caption` → `text`，逐字顺序）、**没有 `.trim()`**（首尾空白原样进消息正文，
 *   与 title 类端点的 trim 故意不同，逐字保留）、**截 1000**（先取后截）；
 *   显式 JSON null 是 `JsonPrimitive`，`.content` 得字面量 `"null"` 字符串（非空，
 *   逐字语义）；对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - **对端 E2EE 拒绝判定**：`body0.startsWith("E2EE:") ||
 *   (body0.startsWith("{") && body0.contains("\"ciphertext\""))` → 400
 *   `"cannot edit peer E2EE message"`，文案逐字——只认既有消息的**正文原文**，
 *   与请求体无关；
 * - **内容组装**：`caption.isBlank()` → 保持 `body0` 不变（纯空白 caption 等于不编辑正文，
 *   逐字语义）；否则**媒体卡片首行保留**：`body0.lines()` 若超过一行，取首行 + `"\n"` +
 *   caption（逐字：`lines.first() + "\n" + caption`），只有单行才直接用 caption；
 * - 校验顺序刻意与原处理器一致（messageId 必填（纯函数）→ 消息存在（处理器）→
 *   归属（处理器）→ 成员检查（处理器）→ E2EE 拒绝（纯函数判定）→ editOwn），
 *   纯函数只负责抽取、校验、判定与组装，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里；
 * - 响应 `put("caption", caption.take(200))` 的 200 截断留在处理器（响应组装，
 *   非请求语义）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotEditMessageCaptionFields(
    val messageId: String,
    val caption: String,
)

internal sealed interface BotEditMessageCaptionFieldsResult {
    data class Ok(val fields: BotEditMessageCaptionFields) : BotEditMessageCaptionFieldsResult
    data object MissingMessageId : BotEditMessageCaptionFieldsResult
}

internal fun parseBotEditMessageCaptionFields(obj: JsonObject): BotEditMessageCaptionFieldsResult {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：caption 双别名（caption → text）+ 无 trim + 截 1000，逐字保留；
    // 显式 JSON null 得字面量 "null"；对象/数组型在 ?.jsonPrimitive 处大声失败。
    val caption = (obj["caption"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1000)
    if (messageId.isBlank()) return BotEditMessageCaptionFieldsResult.MissingMessageId
    return BotEditMessageCaptionFieldsResult.Ok(BotEditMessageCaptionFields(messageId, caption))
}

/**
 * 与原处理器逐字一致的**对端 E2EE 消息拒绝判定**：只认既有消息正文原文——
 * 以 `E2EE:` 开头、或以 `{` 开头且含 `"ciphertext"` 的正文都视为对端加密信封，
 * bot 纯文本卡片不允许编辑（400 `"cannot edit peer E2EE message"`，文案逐字）。
 */
internal fun isPeerE2eeContent(body: String): Boolean =
    body.startsWith("E2EE:") || (body.startsWith("{") && body.contains("\"ciphertext\""))

/**
 * 与原处理器逐字一致的编辑后正文组装：
 * caption 为空/纯空白 → 保持原正文（等于没改）；否则单行正文直接换成 caption，
 * 多行正文（媒体卡片）只重写首行之后的 caption 部分（`首行 + "\n" + caption`）。
 */
internal fun buildBotEditCaptionContent(body0: String, caption: String): String =
    if (caption.isBlank()) body0 else {
        val lines = body0.lines()
        if (lines.size <= 1) caption else (lines.first() + "\n" + caption)
    }
