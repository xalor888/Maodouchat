package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendDocumentFields(
    val chatId: String,
    val fileName: String,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendDocumentFieldsResult {
    data class Ok(val fields: BotSendDocumentFields) : BotSendDocumentFieldsResult
    data object MissingRequired : BotSendDocumentFieldsResult
}

internal fun parseBotSendDocumentFields(obj: JsonObject): BotSendDocumentFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val fileName = (obj["fileName"] ?: obj["filename"])?.jsonPrimitive?.content.orEmpty().trim().take(120).ifBlank { "document.bin" }
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    val b64 = (obj["fileBase64"] ?: obj["document"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendDocumentFieldsResult.MissingRequired
    return BotSendDocumentFieldsResult.Ok(BotSendDocumentFields(chatId, fileName, caption, b64))
}

/** 与原处理器逐字一致的文件体积上限（8MB）。 */
internal const val BOT_DOCUMENT_MAX_BYTES = 8 * 1024 * 1024

internal sealed interface BotDocumentBytesResult {
    data class Ok(val bytes: ByteArray) : BotDocumentBytesResult
    data object InvalidBase64 : BotDocumentBytesResult
    data object TooLarge : BotDocumentBytesResult
}

internal fun decodeBotDocumentBytes(b64: String): BotDocumentBytesResult {
    val bytes = runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), ""))
    }.getOrNull()
    if (bytes == null || bytes.isEmpty()) return BotDocumentBytesResult.InvalidBase64
    if (bytes.size > BOT_DOCUMENT_MAX_BYTES) return BotDocumentBytesResult.TooLarge
    return BotDocumentBytesResult.Ok(bytes)
}

/**
 * 与原处理器逐字一致的 FILE 消息内容组装（含 4000 截断）。
 * `byteSize` 取解码后真实字节数（原处理器用 `bytes.size`，不是输入字符串长度）。
 */
internal fun buildBotDocumentContent(fileName: String, caption: String, byteSize: Int): String =
    buildString {
        append("📎 ")
        append(fileName)
        append(" (")
        append(byteSize)
        append(" bytes)")
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        // Bot plaintext channel only — not E2EE peer attachment pipeline
        append("\n[botFileName:")
        append(fileName)
        append("]")
        append("\n[botFileSize:")
        append(byteSize)
        append("]")
    }.take(4000)
