package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendVoiceFields(
    val chatId: String,
    val durationSec: Int,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendVoiceFieldsResult {
    data class Ok(val fields: BotSendVoiceFields) : BotSendVoiceFieldsResult
    data object MissingRequired : BotSendVoiceFieldsResult
}

internal fun parseBotSendVoiceFields(obj: JsonObject): BotSendVoiceFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(200)
    val b64 = (obj["fileBase64"] ?: obj["voice"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendVoiceFieldsResult.MissingRequired
    return BotSendVoiceFieldsResult.Ok(BotSendVoiceFields(chatId, duration, caption, b64))
}

/** 与原处理器逐字一致的语音体积上限（4MB）。 */
internal const val BOT_VOICE_MAX_BYTES = 4 * 1024 * 1024

/**
 * 与原处理器逐字一致的语音体积累积（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）或空输入**返回 0 而不是报错**——原处理器用
 * `runCatching { ... }.getOrDefault(0)`，坏 base64 的 voice 只是内容里不拼体积段，
 * 不触发 400（与 sendDocument 的 `InvalidBase64` 语义故意不同，零行为改动）。
 */
internal fun measureBotVoiceSize(b64: String): Int =
    if (b64.isNotBlank()) {
        runCatching {
            java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
        }.getOrDefault(0)
    } else 0

/**
 * 与原处理器逐字一致的 VOICE 消息内容组装（含 4000 截断）。
 * `byteSize` 取 [measureBotVoiceSize] 的返回值（0 时模板里不出现体积段）。
 */
internal fun buildBotVoiceContent(durationSec: Int, caption: String, byteSize: Int): String =
    buildString {
        append("🎤 voice")
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
        append("\n[botVoiceSize:")
        append(byteSize)
        append("]")
    }.take(4000)
