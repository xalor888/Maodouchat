package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendSticker` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、`editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、
 * `sendLocation` 之后第八块）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotMediaRoutes` 的 `/api/bot/sendSticker` 处理器里的**抽取 / 内容组装**
 * 逻辑收敛为纯函数，行为与搬移前逐行一致——包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 *   但注意**显式 JSON null 不是类型错**：`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   `chatId`/`emoji` 显式 null 时 `.content` 为字符串 `"null"`，走 `orEmpty()` 后是
 *   非空字符串——原处理器逐字如此（`isBlank()` 判不住 `"null"`），测试特意钉住；
 * - `emoji`→`sticker`→`text` 别名优先级（`?:` 接在 `jsonPrimitive` 之前：
 *   主字段存在但为 null/显式 JSON null 时**不**穿透到别名，逐字保留）、
 *   `emoji` `trim()` 后截断 16、`pack` `trim()` 后截断 40、
 *   `chatId/emoji` 双必填（`chatId/emoji required`，400）；
 * - 内容模板逐字保留：emoji 正文 + pack 非空时换行拼 `[stickerPack:<pack>]`。
 *
 * 校验顺序刻意与原处理器一致（必填 → 成员检查在处理器里），纯函数只负责
 * 抽取与组装，不碰仓库 / 限流 / 功能开关 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendStickerFields(
    val chatId: String,
    val emoji: String,
    val pack: String,
)

internal sealed interface BotSendStickerFieldsResult {
    data class Ok(val fields: BotSendStickerFields) : BotSendStickerFieldsResult
    data object MissingRequired : BotSendStickerFieldsResult
}

/** 与原处理器逐字一致的 `sendSticker` 字段抽取（含别名优先级与截断）。 */
internal fun parseBotSendStickerFields(obj: JsonObject): BotSendStickerFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val emoji = (obj["emoji"] ?: obj["sticker"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().trim().take(16)
    val pack = obj["pack"]?.jsonPrimitive?.content.orEmpty().trim().take(40)
    if (chatId.isBlank() || emoji.isBlank()) return BotSendStickerFieldsResult.MissingRequired
    return BotSendStickerFieldsResult.Ok(BotSendStickerFields(chatId, emoji, pack))
}

/** 与原处理器逐字一致的 STICKER 消息内容组装（emoji + 可选的 pack 段）。 */
internal fun buildBotStickerContent(emoji: String, pack: String): String =
    buildString {
        append(emoji)
        if (pack.isNotBlank()) {
            append("\n[stickerPack:")
            append(pack)
            append("]")
        }
    }
