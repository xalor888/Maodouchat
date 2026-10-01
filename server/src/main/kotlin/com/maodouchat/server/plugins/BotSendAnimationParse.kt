package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendAnimation` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable` 之后
 * **第四十二块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendAnimation`
 * 处理器里内联的**抽取 / 校验 / 解码 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；
 * - `caption` 取 `obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)`，同样
 *   **没有 `.trim()`**（注意与 `sendTable` 表头单元格的 trim 语义不同，逐字保留）；
 *   对象 / 数组型 caption 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]（大声失败，
 *   路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - `b64` 走四别名优先级 `animationBase64` → `gifBase64` → `fileBase64` → `data`，
 *   `(obj["animationBase64"] ?: obj["gifBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()`——
 *   **显式 JSON null 不回退**：`?:` 判的是 Kotlin null，不是 `JsonNull`；
 *   `JsonNull` 本身是 `JsonPrimitive`，`.content` 得字面量 `"null"` 字符串——
 *   非空、进 `Ok`、解码得 3 字节（`"null"` 恰好是合法 base64 字母表），逐字语义、
 *   特意钉住；对象 / 数组型别名键同样在 `?.jsonPrimitive` 处大声失败；
 * - **合并必填**（`chatId.isBlank() || b64.isBlank()`→400 `"chatId/animationBase64 required"`，
 *   文案逐字），与 sendPhoto/sendDocument 的「空媒体一律拒绝」一致（9.138）；
 * - base64 解码：data-URI 前缀剥离（`substringAfter(',')`）+ 空白剔除，`runCatching`
 *   包住——**坏 base64 走宽容分支**：解码抛错只判 `size = 0`，不 400（与 sendPhoto 的
 *   「坏 base64 判 400 `invalid base64`」故意不同，逐字保留）；
 * - 体积上限 8MB（`animation too large (max 8MB)`，413）；
 * - 内容模板：`"✨ gif/animation"` + size>0 时的 `" (NB)"` + 非空 caption 行 +
 *   `"[botAnimSize:N]"`，整体 `.take(4000)` 逐字保留；
 * - 校验顺序刻意与原处理器一致（媒体上传开关（处理器，解析之前）→ 合并必填
 *   （纯函数）→ 成员检查（处理器）→ 解码 → 体积），纯函数只负责抽取、校验、解码
 *   与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendAnimationFields(
    val chatId: String,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendAnimationFieldsResult {
    data class Ok(val fields: BotSendAnimationFields) : BotSendAnimationFieldsResult
    data object MissingRequired : BotSendAnimationFieldsResult
}

internal fun parseBotSendAnimationFields(obj: JsonObject): BotSendAnimationFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    // 注意：四别名优先级逐字保留；显式 JSON null 不回退——JsonNull 是 JsonPrimitive，
    // .content 得字面量 "null"（非空→Ok，逐字怪语义，特意钉住）。
    val b64 = (obj["animationBase64"] ?: obj["gifBase64"] ?: obj["fileBase64"] ?: obj["data"])
        ?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendAnimationFieldsResult.MissingRequired
    return BotSendAnimationFieldsResult.Ok(BotSendAnimationFields(chatId, caption, b64))
}

/** 与原处理器逐字一致的动画体积上限（8MB）。 */
internal const val BOT_ANIMATION_MAX_BYTES = 8 * 1024 * 1024

/**
 * 与原处理器逐字一致的 base64 解码取字节数（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）**不抛**，只返回 0——原处理器用
 * `runCatching { ... }.getOrDefault(0)`，与 sendPhoto 的「坏 base64 判 400」
 * 故意不同，零行为改动。
 */
internal fun decodeBotAnimationSize(b64: String): Int =
    if (b64.isBlank()) 0
    else runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
    }.getOrDefault(0)

/**
 * 与原处理器逐字一致的 GIF/动画消息内容组装（含 4000 截断）。
 * `byteSize` 取 [decodeBotAnimationSize] 的解码结果。
 */
internal fun buildBotAnimationContent(caption: String, byteSize: Int): String =
    buildString {
        append("✨ gif/animation")
        if (byteSize > 0) {
            append(" (")
            append(byteSize)
            append("B)")
        }
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botAnimSize:")
        append(byteSize)
        append("]")
    }.take(4000)
