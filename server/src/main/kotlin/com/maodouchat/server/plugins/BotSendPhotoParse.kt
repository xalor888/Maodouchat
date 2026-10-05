package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// base64 空白剔除：每次请求都在重新编译，提到文件级复用。
private val base64WhitespaceRegex = Regex("\\s")

internal data class BotSendPhotoFields(
    val chatId: String,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendPhotoFieldsResult {
    data class Ok(val fields: BotSendPhotoFields) : BotSendPhotoFieldsResult
    data object MissingRequired : BotSendPhotoFieldsResult
}

internal fun parseBotSendPhotoFields(obj: JsonObject): BotSendPhotoFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    val b64 = (obj["photoBase64"] ?: obj["photo"] ?: obj["fileBase64"] ?: obj["data"])
        ?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendPhotoFieldsResult.MissingRequired
    return BotSendPhotoFieldsResult.Ok(BotSendPhotoFields(chatId, caption, b64))
}

/** 与原处理器逐字一致的图片体积上限（5MB）。 */
internal const val BOT_PHOTO_MAX_BYTES = 5 * 1024 * 1024

internal fun decodeBotPhotoBytes(b64: String): ByteArray? {
    val bytes = runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace(base64WhitespaceRegex, ""))
    }.getOrNull()
    return if (bytes == null || bytes.isEmpty()) null else bytes
}

/**
 * 与原处理器逐字一致的 IMAGE 消息内容组装（含 4000 截断）。
 * `byteSize` 取 [decodeBotPhotoBytes] 的解码结果长度（非空才走到这里）。
 */
internal fun buildBotPhotoContent(caption: String, byteSize: Int): String =
    buildString {
        append("🖼 photo ")
        append(byteSize)
        append("B")
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botPhotoSize:")
        append(byteSize)
        append("]")
    }.take(4000)
