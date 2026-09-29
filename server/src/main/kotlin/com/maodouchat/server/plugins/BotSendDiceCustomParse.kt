package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendDiceCustom` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、
 * `sendDice` 之后**第十三块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPollQuizRoutes` 的 `/api/bot/sendDiceCustom` 处理器里的**抽取 / 校验 /
 * 消息组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `sides` 用 `?.jsonPrimitive?.content?.toIntOrNull() ?: 6`——显式非数字
 *   （如 `"abc"`、浮点 `5.5`→content `"5.5"`、显式 JSON null→content `"null"`）
 *   **直接回 6**，而不是报错。注意这与 `sendDice`（第十二块）**故意不同**：
 *   `sendDice` 的 `?:` 后面是 emoji 映射默认（穿透到映射），`sendDiceCustom` 的
 *   `?:` 后面就是字面 `6`（穿透到常量）——两个端点逐字语义如此，特意钉住；
 * - 结果再 `coerceIn(2, 100)`——显式 sides 也会被钳制（1→2、1000→100、负数→2）；
 * - 唯一必填是 `chatId`（空白判缺，`chatId required`，400）；
 * - 内容模板逐字保留：`"DICE:$sides|$value|bot dice roll"`——注意它**不用 emoji**，
 *   与 `sendDice` 的 `"{emoji ?: 🎲} {value}/{sides}"` 是两套模板；
 * - 校验顺序刻意与原处理器一致（群玩法开关（处理器）→ 必填（纯函数）→
 *   成员检查（处理器）），纯函数只负责抽取、校验与组装，
 *   不碰仓库 / 限流 / 随机数 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendDiceCustomFields(
    val chatId: String,
    /** 解析并钳制后的面数（2..100）。 */
    val sides: Int,
)

internal sealed interface BotSendDiceCustomFieldsResult {
    data class Ok(val fields: BotSendDiceCustomFields) : BotSendDiceCustomFieldsResult
    data object MissingRequired : BotSendDiceCustomFieldsResult
}

internal fun parseBotSendDiceCustomFields(obj: JsonObject): BotSendDiceCustomFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在 toIntOrNull() 之后——显式 sides 非数字/缺省/显式 null 都直接回 6
    //（与 sendDice 穿透到 emoji 映射默认故意不同，原处理器逐字语义）。
    val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6).coerceIn(2, 100)
    if (chatId.isBlank()) return BotSendDiceCustomFieldsResult.MissingRequired
    return BotSendDiceCustomFieldsResult.Ok(BotSendDiceCustomFields(chatId, sides))
}

/**
 * 与原处理器逐字一致的自定义骰子消息组装：`"DICE:$sides|$value|bot dice roll"`。
 */
internal fun buildBotDiceCustomContent(sides: Int, value: Int): String =
    "DICE:$sides|$value|bot dice roll"
