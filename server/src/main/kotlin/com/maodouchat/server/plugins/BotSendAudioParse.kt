package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendAudio` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation` 之后**第四十三块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendAudio`
 * 处理器里内联的**抽取 / 校验 / 解码 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；
 * - `title` 取 `(obj["title"] ?: obj["fileName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)`：
 *   **双别名**（`title` → `fileName`）、**有 `.trim()`**（与 chatId/caption 故意不同，
 *   逐字保留）、截 80；显式 JSON null 得字面量 `"null"`（trim 后仍非空，保留为标题）；
 * - `duration` 取 `(obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0`：
 *   **双别名**、非数字 / 坏类型字面量 → `toIntOrNull()` 得 null → 回 `0`（不是大声失败，
 *   逐字语义）；对象 / 数组型 duration 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - `caption` 取 `obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)`，**没有
 *   `.trim()`**（与 title 的 trim 语义不同，逐字保留）；
 * - `b64` 走三别名优先级 `audioBase64` → `fileBase64` → `data`，
 *   `(obj["audioBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()`——
 *   **显式 JSON null 不回退**：`?:` 判的是 Kotlin null，不是 `JsonNull`；
 *   `JsonNull` 本身是 `JsonPrimitive`，`.content` 得字面量 `"null"` 字符串——
 *   非空、进 `Ok`、解码得 3 字节（`"null"` 恰好是合法 base64 字母表），逐字语义、
 *   特意钉住；对象 / 数组型别名键同样在 `?.jsonPrimitive` 处大声失败；
 * - **合并必填**（`chatId.isBlank() || b64.isBlank()`→400 `"chatId/audioBase64 required"`，
 *   文案逐字），与 sendPhoto/sendDocument 的「空媒体一律拒绝」一致（9.138）；
 * - base64 解码：data-URI 前缀剥离（`substringAfter(',')`）+ 空白剔除，`runCatching`
 *   包住——**坏 base64 走宽容分支**：解码抛错只判 `size = 0`，不 400（与 sendPhoto 的
 *   「坏 base64 判 400 `invalid base64`」故意不同，逐字保留）；
 * - 体积上限 10MB（`audio too large (max 10MB)`，413）；
 * - 内容模板：`"🎵 audio"` + 非空 title 的 `" <title>"` + duration>0 的 `" <N>s"` +
 *   size>0 的 `" (NB)"` + 非空 caption 行 + `"[botAudioSize:N]"`，整体 `.take(4000)`
 *   逐字保留（注意 duration ≤ 0（包括负数与 0）不拼接时长段，逐字语义）；
 * - 校验顺序刻意与原处理器一致（媒体上传开关（处理器，解析之前）→ 合并必填
 *   （纯函数）→ 成员检查（处理器）→ 解码 → 体积），纯函数只负责抽取、校验、解码
 *   与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendAudioFields(
    val chatId: String,
    val title: String,
    val duration: Int,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendAudioFieldsResult {
    data class Ok(val fields: BotSendAudioFields) : BotSendAudioFieldsResult
    data object MissingRequired : BotSendAudioFieldsResult
}

internal fun parseBotSendAudioFields(obj: JsonObject): BotSendAudioFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：title 双别名 + trim + 截 80，逐字保留；显式 JSON null 得字面量 "null"。
    val title = (obj["title"] ?: obj["fileName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)
    // 注意：duration 双别名 + toIntOrNull 回 0（非数字不抛）；对象/数组型在 ?.jsonPrimitive 处大声失败。
    val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    // 注意：三别名优先级逐字保留；显式 JSON null 不回退——JsonNull 是 JsonPrimitive，
    // .content 得字面量 "null"（非空→Ok，逐字怪语义，特意钉住）。
    val b64 = (obj["audioBase64"] ?: obj["fileBase64"] ?: obj["data"])
        ?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendAudioFieldsResult.MissingRequired
    return BotSendAudioFieldsResult.Ok(BotSendAudioFields(chatId, title, duration, caption, b64))
}

/** 与原处理器逐字一致的音频体积上限（10MB）。 */
internal const val BOT_AUDIO_MAX_BYTES = 10 * 1024 * 1024

/**
 * 与原处理器逐字一致的 base64 解码取字节数（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）**不抛**，只返回 0——原处理器用
 * `runCatching { ... }.getOrDefault(0)`（外层还有 `isNotBlank` 守卫，空串同样判 0），
 * 与 sendPhoto 的「坏 base64 判 400」故意不同，零行为改动。
 */
internal fun decodeBotAudioSize(b64: String): Int =
    if (b64.isBlank()) 0
    else runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
    }.getOrDefault(0)

/**
 * 与原处理器逐字一致的音频消息内容组装（含 4000 截断）。
 * `byteSize` 取 [decodeBotAudioSize] 的解码结果。
 */
internal fun buildBotAudioContent(title: String, duration: Int, caption: String, byteSize: Int): String =
    buildString {
        append("🎵 audio")
        if (title.isNotBlank()) {
            append(" ")
            append(title)
        }
        if (duration > 0) {
            append(" ")
            append(duration)
            append("s")
        }
        if (byteSize > 0) {
            append(" (")
            append(byteSize)
            append("B)")
        }
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botAudioSize:")
        append(byteSize)
        append("]")
    }.take(4000)
