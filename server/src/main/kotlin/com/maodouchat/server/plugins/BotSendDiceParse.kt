package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendDice` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、`sendPoll`
 * 之后**第十二块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPollRoutes` 的 `/api/bot/sendDice` 处理器里的**抽取 / 校验 /
 * 消息组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `emoji` **不 trim**：`.content.orEmpty()` 原样保留，只用 `takeIf { it.isNotBlank() }`
 *   判空——纯空格是「无 emoji」（null），但 `"  🏀"`（带前导空格）是非空白、
 *   原样保留且 `when` 匹配不到（`when` 是全字串比较）→ 兜底 6 面（逐字语义，特意钉住）；
 * - `sides` 用 `?.jsonPrimitive?.content?.toIntOrNull()` 接 `?:`——显式非数字
 *   （如 `"abc"`、浮点 `5.5`→content `"5.5"`、显式 JSON null→content `"null"`）
 *   **穿透到 emoji 映射默认**，而不是报错（与 sendPoll/sendLocation 的穿透同一族语义）；
 * - `when (diceEmoji)`：`"🏀"`/`"⚽"`→5、`"🎰"`→64、其余（含 null）→6；
 * - 整条链结果再 `coerceIn(2, 100)`——显式 sides 也会被钳制（1→2、1000→100、负数→2）；
 * - 唯一必填是 `chatId`（空白判缺）；校验顺序刻意与原处理器一致（必填（纯函数）→
 *   成员检查（处理器）→ 群玩法开关（处理器）），纯函数只负责抽取、校验与组装，
 *   不碰仓库 / 限流 / 随机数 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendDiceFields(
    val chatId: String,
    /** emoji 字段原样值（未 trim）；空白/缺省为 null。 */
    val diceEmoji: String?,
    /** 解析并钳制后的面数（2..100）。 */
    val sides: Int,
)

internal sealed interface BotSendDiceFieldsResult {
    data class Ok(val fields: BotSendDiceFields) : BotSendDiceFieldsResult
    data object MissingRequired : BotSendDiceFieldsResult
}

internal fun parseBotSendDiceFields(obj: JsonObject): BotSendDiceFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // emoji 语义映射（对齐 Telegram 骰子）：🎲🎯🎳 6 面、🏀⚽ 5 面、🎰 64 面；显式 sides 优先。
    // 注意：emoji 不 trim，when 是全字串比较——逐字语义。
    val diceEmoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().takeIf { it.isNotBlank() }
    // 注意：?: 接在 toIntOrNull() 之后——显式 sides 非数字时穿透到 emoji 映射默认（原处理器逐字语义）。
    val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: when (diceEmoji) {
            "🏀", "⚽" -> 5
            "🎰" -> 64
            else -> 6
        }).coerceIn(2, 100)
    if (chatId.isBlank()) return BotSendDiceFieldsResult.MissingRequired
    return BotSendDiceFieldsResult.Ok(BotSendDiceFields(chatId, diceEmoji, sides))
}

/**
 * 与原处理器逐字一致的骰子消息组装：`"{emoji ?: 🎲} {value}/{sides}"`。
 */
internal fun buildBotDiceContent(diceEmoji: String?, value: Int, sides: Int): String =
    "${diceEmoji ?: "🎲"} $value/$sides"
