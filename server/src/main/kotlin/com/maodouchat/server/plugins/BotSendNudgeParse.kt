package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendNudge` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage` 之后**第十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotMessagingVariantsRoutes` 的 `/api/bot/sendNudge` 处理器里的
 * **抽取 / 必填校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 *   但注意**显式 JSON null 不是类型错**：`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   显式 null 时 `.content` 为字符串 `"null"`——原处理器逐字如此（`isBlank()` 判不住
 *   `"null"`，于是显式 null 的 chatId 被当成合法非空 id，显式 null 的 text 组装出
 *   `"👋 null"`），测试特意钉住；
 * - `text` 截断 `take(80)` **不 trim**（原处理器逐字如此：前导空格会计入 80 上限，
 *   且 `"  "` 这样的全空白 note 走 `isNotBlank()` 判空分支组装 `"👋 nudge"`）；
 * - 唯一必填是 `chatId`（`chatId required`，400）：`text` 缺/空只影响内容模板，
 *   不触发 400。
 *
 * 校验顺序刻意与原处理器一致（功能门 `isNudgeEnabled` 与成员检查在处理器里，
 * 纯函数只负责抽取 / 必填校验 / 内容组装），不碰仓库 / 限流 / fanout / 响应——
 * 那些副作用仍留在处理器里。评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列
 * 的契约（未知键忽略、缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它
 * 变成可被测试钉住的形态，零行为改动。
 */
internal data class BotSendNudgeFields(
    val chatId: String,
    val note: String,
)

internal sealed interface BotSendNudgeFieldsResult {
    data class Ok(val fields: BotSendNudgeFields) : BotSendNudgeFieldsResult
    data object MissingRequired : BotSendNudgeFieldsResult
}

/** 与原处理器逐字一致的 `sendNudge` 字段抽取（含 80 截断与 chatId 单必填）。 */
internal fun parseBotSendNudgeFields(obj: JsonObject): BotSendNudgeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：原处理器对 text 只做 take(80)、不 trim，逐字保留。
    val note = obj["text"]?.jsonPrimitive?.content.orEmpty().take(80)
    if (chatId.isBlank()) return BotSendNudgeFieldsResult.MissingRequired
    return BotSendNudgeFieldsResult.Ok(BotSendNudgeFields(chatId, note))
}

/** 与原处理器逐字一致的 NUDGE 消息内容组装（空 note 回退为 `"👋 nudge"`）。 */
internal fun buildBotNudgeContent(note: String): String =
    if (note.isNotBlank()) "👋 $note" else "👋 nudge"
