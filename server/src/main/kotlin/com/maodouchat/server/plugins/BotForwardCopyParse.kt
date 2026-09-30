package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `forwardMessage` / `copyMessage` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom` 之后**第十四块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来逐字相同地内联在
 * `configureBotMessageForwardingRoutes` 的两个处理器（`/api/bot/forwardMessage`、
 * `/api/bot/copyMessage`）里的**抽取 / 必填校验**逻辑收敛为同一个纯函数，行为与搬移前逐行一致——
 * 包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 *   但注意**显式 JSON null 不是类型错**：`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   显式 null 时 `.content` 为字符串 `"null"`——原处理器逐字如此（`isBlank()` 判不住
 *   `"null"`，于是显式 null 的 fromChatId 被当成合法非空 id），测试特意钉住；
 * - 别名链的「不穿透」语义：`fromChatId`→`from_chat_id`、`chatId`→`toChatId`→`to_chat_id`、
 *   `messageId`→`message_id` 的 `?:` 接在 **`jsonPrimitive` 之前**——主字段存在
 *   （哪怕显式 JSON null）即不穿透别名，逐字保留；
 * - 三必填（`fromChatId`/`chatId`/`messageId` 任一缺或 trim 后全空 → 400
 *   `fromChatId/chatId/messageId required`），纯函数里完整复刻。
 *
 * 校验顺序刻意与原处理器一致（功能门 `isMessageForwardingEnabled` 与双成员检查在处理器里，
 * 纯函数只负责抽取与必填校验），不碰仓库 / 限流 / fanout / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotForwardCopyFields(
    val fromChatId: String,
    val toChatId: String,
    val messageId: String,
)

internal sealed interface BotForwardCopyFieldsResult {
    data class Ok(val fields: BotForwardCopyFields) : BotForwardCopyFieldsResult
    data object MissingRequired : BotForwardCopyFieldsResult
}

/** 与原两个处理器逐字一致的转发 / 复制字段抽取（含三字段别名优先级与三必填校验）。 */
internal fun parseBotForwardCopyFields(obj: JsonObject): BotForwardCopyFieldsResult {
    // 注意：?: 接在 jsonPrimitive 之前——主字段存在即不穿透别名（哪怕显式 null），原处理器逐字语义。
    val fromChatId = (obj["fromChatId"] ?: obj["from_chat_id"])?.jsonPrimitive?.content.orEmpty()
    val toChatId = (obj["chatId"] ?: obj["toChatId"] ?: obj["to_chat_id"])?.jsonPrimitive?.content.orEmpty()
    val messageId = (obj["messageId"] ?: obj["message_id"])?.jsonPrimitive?.content.orEmpty()
    if (fromChatId.isBlank() || toChatId.isBlank() || messageId.isBlank()) {
        return BotForwardCopyFieldsResult.MissingRequired
    }
    return BotForwardCopyFieldsResult.Ok(BotForwardCopyFields(fromChatId, toChatId, messageId))
}
