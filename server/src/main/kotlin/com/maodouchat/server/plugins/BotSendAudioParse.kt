package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// base64 空白剔除：每次请求都在重新编译，提到文件级复用。
private val base64WhitespaceRegex = Regex("\\s")

internal data class BotSendAudioFields(
    val chatId: String,
    val title: String,
    val duration: Int,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendAudioFieldsResult {
    data class Ok(val fields: BotSendAudioFields) : BotSendAudioFieldsResult
    data object MissingRequired : BotSendAudioFieldsResult
}

internal fun parseBotSendAudioFields(obj: JsonObject): BotSendAudioFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：title 双别名 + trim + 截 80，逐字保留；显式 JSON null 得字面量 "null"。
    val title = (obj["title"] ?: obj["fileName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)
    // 注意：duration 双别名 + toIntOrNull 回 0（非数字不抛）；对象/数组型在 ?.jsonPrimitive 处大声失败。
    val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    // 注意：三别名优先级逐字保留；显式 JSON null 不回退——JsonNull 是 JsonPrimitive，
    // .content 得字面量 "null"（非空→Ok，逐字怪语义，特意钉住）。
    val b64 = (obj["audioBase64"] ?: obj["fileBase64"] ?: obj["data"])
        ?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendAudioFieldsResult.MissingRequired
    return BotSendAudioFieldsResult.Ok(BotSendAudioFields(chatId, title, duration, caption, b64))
}

/** 与原处理器逐字一致的音频体积上限（10MB）。 */
internal const val BOT_AUDIO_MAX_BYTES = 10 * 1024 * 1024

/**
 * 与原处理器逐字一致的 base64 解码取字节数（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）**不抛**，只返回 0——原处理器用
 * `runCatching { ... }.getOrDefault(0)`（外层还有 `isNotBlank` 守卫，空串同样判 0），
 * 与 sendPhoto 的「坏 base64 判 400」故意不同，零行为改动。
 */
internal fun decodeBotAudioSize(b64: String): Int =
    if (b64.isBlank()) 0
    else runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace(base64WhitespaceRegex, "")).size
    }.getOrDefault(0)

/**
 * 与原处理器逐字一致的音频消息内容组装（含 4000 截断）。
 * `byteSize` 取 [decodeBotAudioSize] 的解码结果。
 */
internal fun buildBotAudioContent(title: String, duration: Int, caption: String, byteSize: Int): String =
    buildString {
        append("🎵 audio")
        if (title.isNotBlank()) {
            append(" ")
            append(title)
        }
        if (duration > 0) {
            append(" ")
            append(duration)
            append("s")
        }
        if (byteSize > 0) {
            append(" (")
            append(byteSize)
            append("B)")
        }
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botAudioSize:")
        append(byteSize)
        append("]")
    }.take(4000)
