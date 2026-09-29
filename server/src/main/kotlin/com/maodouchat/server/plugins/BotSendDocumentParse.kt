package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendDocument` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage` 之后第三块）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotMediaRoutes` 的 `/api/bot/sendDocument` 处理器里的抽取 / 校验 / 内容组装逻辑
 * 收敛为纯函数，行为与搬移前逐行一致——包括：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - 空文件名回落 `"document.bin"`、caption 缺省回 `""`、fileName 120 / caption 500 /
 *   内容 4000 的截断上限、`fileName`→`filename` 与
 *   `fileBase64`→`document`→`data` 的别名优先级；
 * - base64 解码前先 `substringAfter(',')`（剥 data-URI 前缀）再剔除空白字符；
 *   解码失败或结果为空 → `InvalidBase64`，超过 8MB → `TooLarge`。
 *
 * 校验顺序刻意与原处理器一致（必填 → 成员检查在处理器里 → base64 → 体积），
 * 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
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
