package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendCode` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote` 之后**第三十七块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendCode`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotCodeFields]，
 * 围栏组装收敛为 [buildBotCodeContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**——针对 `chatId`、`code`、
 *   `language`（都是 `?.jsonPrimitive`：对象 / 数组型值在此抛；显式 JSON null
 *   本身就是 `JsonPrimitive` 的一种，`.content` 取到字面量 `"null"` 字符串、
 *   不抛——kotlinx-serialization-json 1.11.0 的 `JsonNull.content` 即 `"null"`，
 *   `sendMarkdown` 第三十五块 CI 已实证；路由层 `StatusPages` 把它映射为
 *   400「参数无效」，不是 500）；
 * - `code` 取 `(obj["code"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(3500)`，
 *   注意三点：**存在性回退**（只有 `code` 键完全缺席才看 `text`；`code` 在但为
 *   显式 JSON null 时仍走 `code` 分支，取到 `"null"` 字符串，不回退、不抛）；
 *   `take(3500)` 作用于 `orEmpty()` **之后**（裁的是 content，不是序列化串）；
 *   字符串 `code` 不带引号进组装（`?.jsonPrimitive?.content`，与 `sendJsonCard`
 *   的 `?.toString()` 怪语义**相反**，特意钉住）；
 * - `language` 取 `obj["language"]?.jsonPrimitive?.content.orEmpty().take(24)`——
 *   缺键得空串（空串 → 无语言围栏，原处理器逐字如此）；显式 null 得 `"null"`
 *   字符串（不抛——注意此时 `isNotBlank()` 为真，会进入带语言围栏分支，特意钉住）；
 *   对象 / 数组型 `language` 在 `?.jsonPrimitive` 处抛（大声失败）；
 * - **双必填**（`chatId.isBlank() || code.isBlank()`→400
 *   `"chatId/code required"`，文案逐字）；判的是裁过 3500 的空白性，
 *   `language` 再长也不参与必填；
 * - 围栏组装：`lang.isNotBlank()` 时 `` ``` `` + lang + `"\n"` + code + `"\n```"`，
 *   否则 `` ``` `` + `"\n"` + code + `"\n```"`——判的是裁过 24 的串，逐字搬入
 *   [buildBotCodeContent]；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   特性开关门控（`isMarkdownEnabled`，拒绝文案 `"markdown disabled by admin"`）
 *   仍在处理器解析之前；纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotCodeFields(
    val chatId: String,
    val code: String,
    val lang: String,
)

internal sealed interface BotCodeFieldsResult {
    data class Ok(val fields: BotCodeFields) : BotCodeFieldsResult
    data object MissingRequired : BotCodeFieldsResult
}

/**
 * `sendCode` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `code`（含 `code`→`text` 的
 * 存在性回退）、再 `language`，然后判必填——对象 / 数组型值在 `?.jsonPrimitive`
 * 处抛 [IllegalArgumentException]（大声失败；显式 null 取到 `"null"` 字符串、
 * 不抛），`language` 缺键得空串（原处理器逐字如此，特意钉住）。
 */
internal fun parseBotCodeFields(obj: JsonObject): BotCodeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val code = (obj["code"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(3500)
    val lang = obj["language"]?.jsonPrimitive?.content.orEmpty().take(24)
    // 注意：双必填——code 判的是裁掉 3500 字符的空白性；language 不参与必填
    // （原处理器逐字如此）。
    if (chatId.isBlank() || code.isBlank()) {
        return BotCodeFieldsResult.MissingRequired
    }
    return BotCodeFieldsResult.Ok(BotCodeFields(chatId, code, lang))
}

/**
 * 与原处理器逐字一致的围栏组装：`lang` 非空（`isNotBlank`，判的是裁过 24 的串）
 * 时 `` ``` `` + lang + `"\n"` + code + `"\n```"`，否则 `` ``` `` + `"\n"` +
 * code + `"\n```"`。
 */
internal fun buildBotCodeContent(code: String, lang: String): String =
    if (lang.isNotBlank()) "```" + lang + "\n" + code + "\n```"
    else "```\n" + code + "\n```"
