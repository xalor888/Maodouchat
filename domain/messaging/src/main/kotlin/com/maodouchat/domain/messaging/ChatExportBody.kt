package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType

/** 聊天导出纯文本簇：消息正文渲染与导出文件名净化（原 app 侧 ChatExport 内联逻辑，逐字平移）。 */
object ChatExportBody {

    const val FORMAT_VERSION = 1

    // 导出单行：SYSTEM/SK_DIST 或空正文返回 null（调用方直接跳过，与原内联逻辑一致）。
    fun formatLine(
        message: Message,
        ownerId: String,
        resolveSenderName: (String) -> String,
    ): String? {
        if (message.type == MessageType.SYSTEM || message.type == MessageType.SK_DIST) return null
        val body = renderBody(message)
        if (body.isEmpty()) return null
        val sender = if (message.senderId == ownerId) "我" else resolveSenderName(message.senderId)
        return "[${formatAiTaskDue(message.timestamp)}] ${sender.ifBlank { message.senderId }}: $body"
    }

    // 导出全文：头（标题/版本/导出时间/count）+ 逐行；exportedAtText 由调用方按 locale 格式化。
    fun buildText(
        chatName: String,
        ownerId: String,
        resolveSenderName: (String) -> String,
        messages: List<Message>,
        exportedAtText: String,
    ): String {
        val sb = StringBuilder()
        sb.append("=== ").append(chatName.ifBlank { "-" }).append(" ===\n")
        sb.append("format_version=").append(FORMAT_VERSION).append("\n")
        sb.append("exported_at=").append(exportedAtText).append("\n")
        sb.append("count=").append(messages.size).append("\n\n")
        messages.forEach { m -> formatLine(m, ownerId, resolveSenderName)?.let { sb.append(it).append("\n") } }
        return sb.toString()
    }

    fun renderBody(message: Message): String = when (message.type) {
        MessageType.TEXT, MessageType.MARKDOWN, MessageType.GIF -> message.parsedContent()
        MessageType.IMAGE -> "[图片]"
        MessageType.STICKER -> "[表情]"
        MessageType.LOCATION -> "[位置]"
        MessageType.VOICE -> "[语音]"
        MessageType.VIDEO -> "[视频]"
        MessageType.FILE -> message.meta.fileName?.let { "[文件] $it" } ?: "[文件]"
        MessageType.NUDGE -> "[戳一戳]"
        MessageType.REVOKED -> "[消息已撤回]"
        MessageType.SYSTEM, MessageType.SK_DIST -> ""
    }

    private val unsafeCharsRegex = Regex("[\\\\/:*?\"<>|]")

    fun sanitizeExportFileName(fileName: String): String =
        fileName.replace(unsafeCharsRegex, "_").take(80)
}
