package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// base64 空白剔除：每次请求都在重新编译，提到文件级复用。
private val base64WhitespaceRegex = Regex("\\s")

internal data class BotSendVideoFields(
    val chatId: String,
    val durationSec: Int,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendVideoFieldsResult {
    data class Ok(val fields: BotSendVideoFields) : BotSendVideoFieldsResult
    data object MissingRequired : BotSendVideoFieldsResult
}

internal fun parseBotSendVideoFields(obj: JsonObject): BotSendVideoFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val b64 = (obj["videoBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendVideoFieldsResult.MissingRequired
    return BotSendVideoFieldsResult.Ok(BotSendVideoFields(chatId, duration, caption, b64))
}

/** 与原处理器逐字一致的视频体积上限（12MB）。 */
internal const val BOT_VIDEO_MAX_BYTES = 12 * 1024 * 1024

internal fun measureBotVideoSize(b64: String): Int =
    if (b64.isNotBlank()) {
        runCatching {
            java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace(base64WhitespaceRegex, "")).size
        }.getOrDefault(0)
    } else 0

/**
 * 与原处理器逐字一致的 VIDEO 消息内容组装（含 4000 截断）。
 * `byteSize` 取 [measureBotVideoSize] 的返回值（0 时模板里不出现体积段），
 * `durationSec` 非正时模板里不出现时长段。
 */
internal fun buildBotVideoContent(durationSec: Int, caption: String, byteSize: Int): String =
    buildString {
        append("🎬 video")
        if (durationSec > 0) {
            append(" ")
            append(durationSec)
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
        append("\n[botVideoSize:")
        append(byteSize)
        append("]")
    }.take(4000)
