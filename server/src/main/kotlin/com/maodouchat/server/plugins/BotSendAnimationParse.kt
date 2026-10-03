package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendAnimationFields(
    val chatId: String,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendAnimationFieldsResult {
    data class Ok(val fields: BotSendAnimationFields) : BotSendAnimationFieldsResult
    data object MissingRequired : BotSendAnimationFieldsResult
}

internal fun parseBotSendAnimationFields(obj: JsonObject): BotSendAnimationFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    // 注意：四别名优先级逐字保留；显式 JSON null 不回退——JsonNull 是 JsonPrimitive，
    // .content 得字面量 "null"（非空→Ok，逐字怪语义，特意钉住）。
    val b64 = (obj["animationBase64"] ?: obj["gifBase64"] ?: obj["fileBase64"] ?: obj["data"])
        ?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendAnimationFieldsResult.MissingRequired
    return BotSendAnimationFieldsResult.Ok(BotSendAnimationFields(chatId, caption, b64))
}

/** 与原处理器逐字一致的动画体积上限（8MB）。 */
internal const val BOT_ANIMATION_MAX_BYTES = 8 * 1024 * 1024

/**
 * 与原处理器逐字一致的 base64 解码取字节数（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）**不抛**，只返回 0——原处理器用
 * `runCatching { ... }.getOrDefault(0)`，与 sendPhoto 的「坏 base64 判 400」
 * 故意不同，零行为改动。
 */
internal fun decodeBotAnimationSize(b64: String): Int =
    if (b64.isBlank()) 0
    else runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
    }.getOrDefault(0)

/**
 * 与原处理器逐字一致的 GIF/动画消息内容组装（含 4000 截断）。
 * `byteSize` 取 [decodeBotAnimationSize] 的解码结果。
 */
internal fun buildBotAnimationContent(caption: String, byteSize: Int): String =
    buildString {
        append("✨ gif/animation")
        if (byteSize > 0) {
            append(" (")
            append(byteSize)
            append("B)")
        }
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botAnimSize:")
        append(byteSize)
        append("]")
    }.take(4000)
