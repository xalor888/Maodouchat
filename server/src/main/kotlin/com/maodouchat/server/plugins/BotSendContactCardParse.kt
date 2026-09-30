package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendContactCard` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge` 之后**第十六块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationCardsRoutes` 的 `/api/bot/sendContactCard` 处理器里的
 * **抽取 / 必填校验 / 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括两处故意保留的「怪」语义：
 *
 * - `name` 字段缺失（或为 JSON null 以外的任何情况使 `?.jsonPrimitive?.content` 为
 *   null）时回默认值 `"contact"`（`take(80)` 作用于回退之后）；但**显式 JSON null
 *   不是缺失**：`JsonNull` 本就是 `JsonPrimitive` 的子类型，显式 null 时 `.content`
 *   为字符串 `"null"`——原处理器逐字如此（`isBlank()` 判不住 `"null"`，组装出
 *   `"> ~card:null~"`），测试特意钉住；
 * - `name` 截断 `take(80)` **不 trim**（原处理器逐字如此）；
 * - 唯一必填是 `chatId`（`chatId required`，400）：`name` 缺/空只影响内容模板，
 *   不触发 400。
 *
 * 校验顺序刻意与原处理器一致（功能门 `isMarkdownEnabled` / `isContactCardEnabled`
 * 与成员检查在处理器里，纯函数只负责抽取 / 必填校验 / 内容组装），不碰仓库 /
 * publish / 日志 / 响应——那些副作用仍留在处理器里。评估结论沿用 G355：bot 侧
 * 手写解析本来就满足 fuzz 系列的契约（未知键忽略、缺省回默认值、坏类型大声失败、
 * 无未处理 500），这里只是把它变成可被测试钉住的形态，零行为改动。
 */
internal data class BotSendContactCardFields(
    val chatId: String,
    val name: String,
)

internal sealed interface BotSendContactCardFieldsResult {
    data class Ok(val fields: BotSendContactCardFields) : BotSendContactCardFieldsResult
    data object MissingRequired : BotSendContactCardFieldsResult
}

/** 与原处理器逐字一致的 `sendContactCard` 字段抽取（含 80 截断与 chatId 单必填）。 */
internal fun parseBotSendContactCardFields(obj: JsonObject): BotSendContactCardFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：原处理器对 name 只做 take(80)、不 trim；缺失回 "contact"，逐字保留。
    val name = (obj["name"]?.jsonPrimitive?.content ?: "contact").take(80)
    if (chatId.isBlank()) return BotSendContactCardFieldsResult.MissingRequired
    return BotSendContactCardFieldsResult.Ok(BotSendContactCardFields(chatId, name))
}

/** 与原处理器逐字一致的联系人名片消息内容组装（模板 `"> ~card:$name~"`）。 */
internal fun buildBotContactCardContent(name: String): String = "> ~card:$name~"
