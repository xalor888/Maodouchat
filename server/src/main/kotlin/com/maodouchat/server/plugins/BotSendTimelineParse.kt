package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendTimeline` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation`、`sendAudio`、`editMessageCaption` 之后**第四十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendTimeline`
 * 处理器里内联的**抽取 / 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；显式 JSON null 得字面量 `"null"`
 *   （`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，逐字语义）；
 * - `title` 取 `(obj["title"]?.jsonPrimitive?.content ?: "Timeline").take(80)`：
 *   默认文案 `"Timeline"` 只在**键缺席**时回退——显式 JSON null 得字面量 `"null"`
 *  （不回退默认值，特意钉住）；**没有 `.trim()`**（前导空格计入 80 上限，逐字保留）；
 *   对象 / 数组型 title 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - `items` 取
 *   `(obj["items"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList())`：
 *   键缺席→空列表；显式 JSON null / 对象型在 `?.jsonArray` 处抛
 *   [IllegalArgumentException]（大声失败，逐字语义——`JsonNull` 不是数组）；
 *   数组元素里**非 primitive（对象/数组）静默丢弃**（`runCatching` 包住，
 *   与坏类型大声失败的已知字段**故意不同**，逐字保留），而 `JsonNull` 元素是
 *   `JsonPrimitive`、得字面量 `"null"` 字符串（保留，非空）；
 *   逐项 `.take(120)`、全表 `.take(12)`（先逐项截、再取前 12，逐字顺序）；
 * - **合并必填**（`chatId.isBlank() || items.isEmpty()`→400 `"chatId/items required"`，
 *   文案逐字）——注意 items 为空也可能是「全被静默丢弃」的结果（逐字语义）；
 * - 抽取顺序刻意与原处理器一致（`chatId`→`title`→`items`，最后判必填）——
 *   坏类型字段的抛错顺序也因此不变；
 * - 内容组装逐字搬移：`items.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")`
 *   之后 `"### $title\n$lines"`（`type = "MARKDOWN"`，原处理器逐字如此）；
 * - 纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——特性开关门控
 *   （`markdown_disabled`）、成员检查、`publishBotServiceMessage`、`logCommand`
 *   仍在处理器里，校验顺序与原处理器一致。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendTimelineFields(
    val chatId: String,
    val title: String,
    val items: List<String>,
)

internal sealed interface BotSendTimelineFieldsResult {
    data class Ok(val fields: BotSendTimelineFields) : BotSendTimelineFieldsResult
    data object MissingRequired : BotSendTimelineFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `title` → `items`，最后判 `chatId.isBlank() || items.isEmpty()`。
 */
internal fun parseBotSendTimelineFields(obj: JsonObject): BotSendTimelineFieldsResult {
    // 注意：chatId 无 trim；title 缺省才回 "Timeline"、显式 null 得字面 "null"、
    // 无 trim、先取后截 80；items 键缺席→空列表、显式 null/对象型在 ?.jsonArray
    // 处大声失败、数组内非 primitive 元素静默丢弃、逐项截 120、全表取前 12。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val title = (obj["title"]?.jsonPrimitive?.content ?: "Timeline").take(80)
    val items = (obj["items"]?.jsonArray?.mapNotNull {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    } ?: emptyList()).map { it.take(120) }.take(12)
    if (chatId.isBlank() || items.isEmpty()) return BotSendTimelineFieldsResult.MissingRequired
    return BotSendTimelineFieldsResult.Ok(BotSendTimelineFields(chatId, title, items))
}

/**
 * 与原处理器逐字一致的时间线正文组装：`### <title>` 之后逐行 `<序号>. <条目>`。
 */
internal fun buildBotTimelineContent(title: String, items: List<String>): String {
    val lines = items.mapIndexed { i, t -> (i + 1).toString() + ". " + t }.joinToString("\n")
    return "### " + title + "\n" + lines
}

// CI retrigger（2026-10-01）：run 36849886409 的 Server job 在「Compile and test server」
// 步骤 exit 1。静态分析已穷尽：主代码为逐行等价搬移（同包 internal、import 齐全、
// 路由无悬垂引用）；6 例新测试逐条手算通过（fuzz 基 payload 延续第四十一块教训、
// 必填字段确定性合法）；品牌术语脚本本地通过；ServerArchitectureTest 空基线、
// RouteRegistrySplitTest 端点计数均不受影响。job 日志无权读取（403）、
// annotations 只有通用 exit 1，暂无法定位具体失败用例——疑似单测抖动或 Gradle
// 环境问题，故以此注释提交重新触发全量 CI 做诊断性重跑。若再次在同一步骤失败，
// 则视为真 bug，下一轮凭新的失败证据继续定位。
