package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendKeyValue` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare` 之后**第三十一块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `sendKeyValue`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotKeyValueFields]，
 * 内容组装收敛为 [buildBotKeyValueContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `key` 取 `obj["key"]?.jsonPrimitive?.content.orEmpty().ifBlank { "key" }.take(40)`——
 *   默认文案接的是**空白性**而非存在性：键缺席**与**键在但空白都得 `"key"`
 *   （`\"null\"` 字面与其它非空白串不触发），**不 trim**（前导空格先被
 *   `ifBlank` 吞掉为空白，计入 `take(40)` 上限前已成 `"key"`）；
 * - `value` 取 `obj["value"]?.jsonPrimitive?.content.orEmpty().take(120)`——
 *   **无默认值**：键缺席得 `""`，键在但空白原样保留空白、**不**回任何默认；
 *   显式 JSON null 得字面 `\"null\"`（`JsonNull` 本身是 `JsonPrimitive`，
 *   `.content` 为 `\"null\"`，**不**被 `orEmpty` 吞掉，特意钉住）；
 * - 单必填（`chatId.isBlank()`→400 `\"chatId required\"`）；`key`/`value` 非必填——
 *   `key` 缺省/空白回 `"key"`，`value` 缺省得 `""`、空白原样保留（组装时不判缺，
 *   原处理器逐字如此，特意钉住）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotKeyValueFields(
    val chatId: String,
    val key: String,
    val value: String,
)

internal sealed interface BotKeyValueFieldsResult {
    data class Ok(val fields: BotKeyValueFields) : BotKeyValueFieldsResult
    data object MissingRequired : BotKeyValueFieldsResult
}

/**
 * `sendKeyValue` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `key`、最后 `value`，然后判必填——
 * 坏类型字段的抛错顺序也因此不变（对象/数组型已知字段的 `?.jsonPrimitive`
 * 抛 [IllegalArgumentException]，大声失败）。
 */
internal fun parseBotKeyValueFields(obj: JsonObject): BotKeyValueFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：key 的默认值接空白性——缺席与空白都得 "key"（"null" 字面等非空白串不触发）；
    // value 无默认值——缺席得 ""，空白原样保留。
    val key = obj["key"]?.jsonPrimitive?.content.orEmpty().ifBlank { "key" }.take(40)
    val value = obj["value"]?.jsonPrimitive?.content.orEmpty().take(120)
    // 注意：单必填——其余字段缺省/空白不判缺（原处理器逐字如此）。
    if (chatId.isBlank()) {
        return BotKeyValueFieldsResult.MissingRequired
    }
    return BotKeyValueFieldsResult.Ok(BotKeyValueFields(chatId, key, value))
}

/**
 * 与原处理器逐字一致的内容组装：`"`" + key + "` = **" + value + "**"`。
 */
internal fun buildBotKeyValueContent(key: String, value: String): String =
    "`" + key + "` = **" + value + "**"
