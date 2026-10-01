package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendTable` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、
 * `sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、
 * `sendKeyValue`、`sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus` 之后**第四十一块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendTable`
 * 处理器里内联的**抽取 / 校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；
 * - `headers` 用 `as? JsonArray` 分支判断——非数组（字符串 / 对象 / 缺省 / 显式 null）
 *   **不是错误**，而是空表头（随即判 `MissingRequired`）；单元格
 *   `(it as? JsonPrimitive)` 让对象 / 数组单元格被**静默丢弃**（不是大声失败，逐字语义、
 *   特意钉住）；`?.content?.trim()?.take(40)` → `filter { it.isNotBlank() }` →
 *   `take(8)`——**先 trim+截 40，再剔空白，最后取前 8**（空白被剔后不足 8 也照收；
 *   与 `sendChecklist` items 同一族语义）；显式 JSON null 是 `JsonPrimitive`、
 *   `.content` 为字面 `"null"` 字符串——trim+take 后非空白、被**保留为表头**
 *   （逐字语义，特意钉住）；
 * - `rows` 同理用 `as? JsonArray`——非数组不是错误而是空行集；**行元素非数组则整行
 *   静默丢弃**（`return@mapNotNull null`，逐字语义）；行内单元格同样 trim+截 40、
 *   但**不做 `isNotBlank` 过滤**（纯空白单元格保留为空串 `""`，逐字语义），每行
 *   `take(8)`；整体 `filter { it.isNotEmpty() }` 剔掉全丢光的行，再 `take(20)`——
 *   顺序逐字保留（先剔空行、后取前 20）；
 * - **合并必填**（`chatId.isBlank() || headers.isEmpty() || rows.isEmpty()`→400
 *   `"chatId/headers/rows required"`，文案逐字），判的是未裁剪串的空白性；
 * - 校验顺序刻意与原处理器一致（markdown 开关（处理器，解析之前）→ 合并必填
 *   （纯函数）→ 成员检查（处理器）），纯函数只负责抽取、校验与组装，不碰仓库 /
 *   限流 / 响应——那些副作用仍留在处理器里；
 * - `chatId` 已知字段类型错时抛 [IllegalArgumentException]（`?.jsonPrimitive`；
 *   路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）——注意 `headers` /
 *   `rows` 不是已知字段的这种反例：它们的 `as?` 分支让坏类型变成"缺失"，不抛。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendTableFields(
    val chatId: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)

internal sealed interface BotSendTableFieldsResult {
    data class Ok(val fields: BotSendTableFields) : BotSendTableFieldsResult
    data object MissingRequired : BotSendTableFieldsResult
}

internal fun parseBotSendTableFields(obj: JsonObject): BotSendTableFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：headers 非数组不是错误而是空表头（随即判缺）；单元格 (as? JsonPrimitive)
    // 让对象/数组单元格静默丢弃；显式 null 得字面 "null" 保留为表头；顺序：trim+截 40 →
    // 剔空白 → 取前 8。
    val headersEl = obj["headers"] as? JsonArray
    val headers = headersEl?.mapNotNull {
        (it as? JsonPrimitive)?.content?.trim()?.take(40)
    }?.filter { it.isNotBlank() }?.take(8).orEmpty()
    // 注意：行元素非数组 → 整行静默丢弃；单元格 trim+截 40 但不剔空白（"" 保留）；
    // 每行取 8；整体先剔全空行、后取前 20。
    val rowsEl = obj["rows"] as? JsonArray
    val rows = rowsEl?.mapNotNull { rowEl ->
        val arr = rowEl as? JsonArray ?: return@mapNotNull null
        arr.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.take(40) }
            .take(8)
    }?.filter { it.isNotEmpty() }?.take(20).orEmpty()
    if (chatId.isBlank() || headers.isEmpty() || rows.isEmpty()) {
        return BotSendTableFieldsResult.MissingRequired
    }
    return BotSendTableFieldsResult.Ok(BotSendTableFields(chatId, headers, rows))
}

/**
 * 与原处理器逐字一致的表格消息内容组装：表头行 + 分隔行 + 数据行拼接；列数以
 * `headers.size` 为准（多余单元格丢弃、不足补空串）。
 */
internal fun buildBotTableContent(headers: List<String>, rows: List<List<String>>): String {
    val headLine = "| " + headers.joinToString(" | ") + " |"
    val sepLine = "| " + headers.joinToString(" | ") { "---" } + " |"
    val bodyLines = rows.joinToString("\n") { r ->
        val cells = (0 until headers.size).map { i -> r.getOrNull(i).orEmpty() }
        "| " + cells.joinToString(" | ") + " |"
    }
    return headLine + "\n" + sepLine + "\n" + bodyLines
}
