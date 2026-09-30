package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendMetric` / `sendCompare` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard` 之后**第三十块**）。
 *
 * 两个端点（`/api/bot/sendMetric`、`/api/bot/sendCompare`）的处理器逐字同构，
 * 只有四处不同：字段名三元组（`label`/`value`/`unit` 对 `left`/`right`）、
 * 各字段的默认文案与截断上限、内容模板——连特性开关门控的拒绝文案都相同
 * （`"markdown_disabled"`），且都在解析之前（仍在处理器里）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把两个处理器里内联的
 * **抽取 / 校验**逻辑收敛为纯函数 [parseBotMetricCompareFields]，内容组装收敛为
 * [buildBotMetricContent] / [buildBotCompareContent]，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - 各文本字段取 `(obj[key]?.jsonPrimitive?.content ?: default).take(cap)`——
 *   默认文案只在**键缺席**时回退：显式 JSON null 得字面 `"null"`（`JsonNull`
 *   本身是 `JsonPrimitive`，`.content` 为 `"null"`，**不**回退默认值，特意钉住）；
 *   键**在但空白**时原样保留空白、**不**回退默认值（`sendMetric` 的 `label` 在、
 *   `value`/`unit` 在而空白都如此，原处理器逐字如此，特意钉住）；
 *   **不 trim**（前导空格计入上限，截断逐字不变）；
 * - 单必填（`chatId.isBlank()`→400 `"chatId required"`）；其余字段非必填——
 *   缺省回默认文案，空白原样保留（组装时不判缺，特意钉住）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   各端点的特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - `sendCompare` 只有两个文本字段：第三个字段槽在它身上恒为 `""`（`keyC = null`
 *   时直接填空字符串、不读键），内容组装不消费它，行为逐字不变。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotMetricCompareFields(
    val chatId: String,
    val first: String,
    val second: String,
    val third: String,
)

internal sealed interface BotMetricCompareFieldsResult {
    data class Ok(val fields: BotMetricCompareFields) : BotMetricCompareFieldsResult
    data object MissingRequired : BotMetricCompareFieldsResult
}

/**
 * `sendMetric` / `sendCompare` 共用的请求体解析。
 *
 * 各端点传入自己的三组 `(key, default, cap)`；`sendCompare` 没有第三个字段，
 * 传 `keyC = null`（此时 [BotMetricCompareFields.third] 恒为 `""`）。
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 keyA、keyB、keyC，最后判必填——
 * 坏类型字段的抛错顺序也因此不变（对象/数组型已知字段的 `?.jsonPrimitive`
 * 抛 [IllegalArgumentException]，大声失败）。
 */
internal fun parseBotMetricCompareFields(
    obj: JsonObject,
    keyA: String, defaultA: String, capA: Int,
    keyB: String, defaultB: String, capB: Int,
    keyC: String?, defaultC: String, capC: Int,
): BotMetricCompareFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接存在性——键缺席才回默认；显式 JSON null 得字面 "null"（非 null，不回退）；
    // 键在但空白原样保留（不回默认）；不 trim，前导空格计入 take 上限（原处理器逐字顺序）。
    val first = (obj[keyA]?.jsonPrimitive?.content ?: defaultA).take(capA)
    val second = (obj[keyB]?.jsonPrimitive?.content ?: defaultB).take(capB)
    val third = if (keyC == null) "" else (obj[keyC]?.jsonPrimitive?.content ?: defaultC).take(capC)
    // 注意：单必填——其余字段缺省/空白不判缺（原处理器逐字如此）。
    if (chatId.isBlank()) {
        return BotMetricCompareFieldsResult.MissingRequired
    }
    return BotMetricCompareFieldsResult.Ok(BotMetricCompareFields(chatId, first, second, third))
}

/**
 * 与原处理器逐字一致的内容组装：`"**" + label + "**  \n`" + value + unit 后缀 + "`"`
 *（unit 非空白时后缀为 `" " + unit`，否则为空；markdown 硬换行的两个空格逐字保留）。
 */
internal fun buildBotMetricContent(label: String, value: String, unit: String): String {
    val unitSuffix = if (unit.isNotBlank()) " " + unit else ""
    return "**" + label + "**  \n`" + value + unitSuffix + "`"
}

/**
 * 与原处理器逐字一致的内容组装：单行对比表格
 * `"| Left | Right |\n| --- | --- |\n| " + left + " | " + right + " |"`。
 */
internal fun buildBotCompareContent(left: String, right: String): String =
    "| Left | Right |\n| --- | --- |\n| " + left + " | " + right + " |"
