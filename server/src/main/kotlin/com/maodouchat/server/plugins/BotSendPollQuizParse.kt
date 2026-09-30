package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendPollQuiz` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard` 之后**第十七块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPollQuizRoutes` 的 `/api/bot/sendPollQuiz` 处理器里的**抽取 / 校验 /
 * 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；注意
 *   `options` 元素是例外——`(it as? JsonPrimitive)` 让对象/数组元素被**静默丢弃**，
 *   不是大声失败（逐字语义，特意钉住）；
 * - `options` 用 `as? JsonArray` 分支判断——非数组（字符串/对象/缺省）**不是错误**，
 *   而是 `emptyList()`（随即因 `< 2` 判 `MissingRequired`）；数组元素
 *   `?.content?.trim()?.take(80)` 后 `filter { it.isNotBlank() }` 再 `take(10)`——
 *   顺序逐字保留：先 trim+截 80，再剔空白，最后取前 10（空白被剔后不足 10 也照收）；
 *   显式 JSON null 是 `JsonPrimitive`、`.content` 为 `"null"` 字符串——**保留为
 *   字面 `"null"` 选项**（与 `sendPoll` 同一族语义，特意钉住）；
 * - `question` 有别名链 `question`→`text`（`?:` 接在字段**存在性**上：`question`
 *   键存在但为显式 JSON null 时**不**穿透到 `text`，得字面 `"null"`）；
 *   `.orEmpty().take(200)` **不 trim**（原处理器逐字如此），前导空格计入 200 上限；
 * - `correctOptionIndex` 用 `?.jsonPrimitive?.content?.toIntOrNull() ?: 0`——
 *   JSON 数字与数字字符串都可（`"2"`→2），垃圾字符串/浮点字符串/显式 null/
 *   缺省一律→0；钳位 `coerceIn(0, options.lastIndex)` 仍在处理器里（纯函数只返回
 *   解析出的原始值，处理器逐字 `safeIdx` 映射）；
 * - 校验顺序刻意与原处理器一致（群玩法开关（处理器，解析之前）→ 必填（纯函数）→
 *   成员检查（处理器）），纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——
 *   那些副作用仍留在处理器里。注意原处理器里 `correct` 的抽取**在必填检查之前**：
 *   `correctOptionIndex` 类型错即使 chatId 缺失也会先抛错——顺序逐字保留。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendPollQuizFields(
    val chatId: String,
    val question: String,
    val options: List<String>,
    val correctOptionIndex: Int,
)

internal sealed interface BotSendPollQuizFieldsResult {
    data class Ok(val fields: BotSendPollQuizFields) : BotSendPollQuizFieldsResult
    data object MissingRequired : BotSendPollQuizFieldsResult
}

internal fun parseBotSendPollQuizFields(obj: JsonObject): BotSendPollQuizFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在字段存在性上——question 键存在（哪怕是显式 JSON null）就不穿透到 text；
    // orEmpty().take(200) 不 trim，前导空格计入上限（原处理器逐字语义）。
    val question = (obj["question"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(200)
    // 注意：as? JsonArray——非数组不是错误而是 emptyList（随即 < 2 判缺）；
    // 元素 (as? JsonPrimitive) 让对象/数组元素静默丢弃，显式 null 得字面 "null" 选项。
    val optionsEl = obj["options"] as? kotlinx.serialization.json.JsonArray
    val options = optionsEl?.mapNotNull {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
    }?.filter { it.isNotBlank() }?.take(10).orEmpty()
    // 注意：correct 抽取在必填检查之前——类型错即使必填缺失也先抛（原处理器逐字顺序）。
    val correct = obj["correctOptionIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    if (chatId.isBlank() || question.isBlank() || options.size < 2) {
        return BotSendPollQuizFieldsResult.MissingRequired
    }
    return BotSendPollQuizFieldsResult.Ok(BotSendPollQuizFields(chatId, question, options, correct))
}

/**
 * 与原处理器逐字一致的测验消息内容组装：`"QUIZ:"` + question（不 trim 原样）+
 * 每个选项 `"|"` 前缀、正确选项多一个 `"*"` 标记，整体 `take(2000)`。
 */
internal fun buildBotPollQuizContent(question: String, options: List<String>, safeIdx: Int): String =
    buildString {
        append("QUIZ:").append(question)
        options.forEachIndexed { i, o ->
            append("|").append(if (i == safeIdx) "*" else "").append(o)
        }
    }.take(2000)
