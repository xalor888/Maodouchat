package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendChecklist` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery` 之后**第十九块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotChatMiscRoutes` 的 `/api/bot/sendChecklist` 处理器里的**抽取 / 校验 /
 * 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；注意
 *   `items` 元素是例外——`(it as? JsonPrimitive)` 让对象/数组元素被**静默丢弃**，
 *   不是大声失败（逐字语义，特意钉住）；
 * - `items` 用 `as? JsonArray` 分支判断——非数组（字符串/对象/缺省）**不是错误**，
 *   而是 `emptyList()`（随即判 `MissingRequired`）；数组元素
 *   `?.content?.trim()?.take(80)` 后 `filter { it.isNotBlank() }` 再 `take(20)`——
 *   顺序逐字保留：先 trim+截 80，再剔空白，最后取前 20（空白被剔后不足 20 也照收）；
 *   显式 JSON null 是 `JsonPrimitive`、`.content` 为 `"null"` 字符串——**保留为
 *   字面 `"null"` 选项**（与 `sendPoll`/`sendPollQuiz` 同一族语义，特意钉住）；
 * - `title` 取 `.orEmpty().take(80)` **不 trim**（原处理器逐字如此），前导空格计入 80 上限；
 *   `title` 非必填——空白时组装只出列表（原处理器 `if (title.isNotBlank())` 逐字保留）；
 * - 校验顺序刻意与原处理器一致（markdown 开关（处理器，解析之前）→ 必填（纯函数：
 *   `chatId.isBlank() || items.isEmpty()`→400）→ 成员检查（处理器）），纯函数只负责
 *   抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：标题非空时 `"**$title**\n"` 前缀 + 每项 `"- [ ] $it"`
 *   换行拼接（整体**无** `take()` 上限——原处理器逐字如此，`title` 截 80 + 每项截 80 +
 *   至多 20 项即是天然上限）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendChecklistFields(
    val chatId: String,
    val title: String,
    val items: List<String>,
)

internal sealed interface BotSendChecklistFieldsResult {
    data class Ok(val fields: BotSendChecklistFields) : BotSendChecklistFieldsResult
    data object MissingRequired : BotSendChecklistFieldsResult
}

internal fun parseBotSendChecklistFields(obj: JsonObject): BotSendChecklistFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：title 不 trim，前导空格计入 80 上限（原处理器逐字语义）；显式 JSON null
    // 得字面 "null"——不是缺省（title 非必填，空白只影响组装前缀）。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().take(80)
    // 注意：as? JsonArray——非数组不是错误而是 emptyList（随即判缺）；
    // 元素 (as? JsonPrimitive) 让对象/数组元素静默丢弃，显式 null 得字面 "null" 选项。
    val itemsEl = obj["items"] as? kotlinx.serialization.json.JsonArray
    val items = itemsEl?.mapNotNull {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
    }?.filter { it.isNotBlank() }?.take(20).orEmpty()
    if (chatId.isBlank() || items.isEmpty()) {
        return BotSendChecklistFieldsResult.MissingRequired
    }
    return BotSendChecklistFieldsResult.Ok(BotSendChecklistFields(chatId, title, items))
}

/**
 * 与原处理器逐字一致的清单消息内容组装：标题非空时 `"**title**\n"` + 每项
 * `"- [ ] item"` 换行拼接。无整体截断（原处理器逐字如此）。
 */
internal fun buildBotChecklistContent(title: String, items: List<String>): String {
    val head = if (title.isNotBlank()) "**$title**\n" else ""
    val bodyMd = items.joinToString("\n") { "- [ ] $it" }
    return head + bodyMd
}
