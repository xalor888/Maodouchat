package com.maodouchat.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录导出（8.54）：把本机会话消息渲染为纯文本并分享。
 * 仅导出本地已解密消息；内容不经网络。上限 [MAX_MESSAGES] 条防止超大会话 OOM。
 */
object ChatExport {

    const val MAX_MESSAGES = 2000

    /**
     * 渲染导出文本。ownerId 用「我」标注本人；senderName 由调用方解析（群内显示成员名）。
     * SYSTEM/SK_DIST 跳过；REVOKED 标 [消息已撤回]。
     */
    fun buildText(
        chatName: String,
        ownerId: String,
        resolveSenderName: (senderId: String) -> String,
        messages: List<Message>,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("=== ").append(chatName.ifBlank { "-" }).append(" ===\n")
        sb.append("exported_at=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(exportedAt))).append("\n")
        sb.append("count=").append(messages.size).append("\n\n")
        messages.forEach { m ->
            if (m.type == MessageType.SYSTEM || m.type == MessageType.SK_DIST) return@forEach
            val sender = if (m.senderId == ownerId) "我" else resolveSenderName(m.senderId)
            val body = renderBody(m)
            if (body.isEmpty()) return@forEach
            sb.append("[").append(ts.format(Date(m.timestamp))).append("] ")
                .append(sender.ifBlank { m.senderId }).append(": ").append(body).append("\n")
        }
        return sb.toString()
    }

    private fun renderBody(m: Message): String = when (m.type) {
        MessageType.TEXT, MessageType.MARKDOWN, MessageType.GIF -> m.parsedContent()
        MessageType.IMAGE -> "[图片]"
        MessageType.STICKER -> "[表情]"
        MessageType.LOCATION -> "[位置]"
        MessageType.VOICE -> "[语音]"
        MessageType.VIDEO -> "[视频]"
        MessageType.FILE -> m.meta.fileName?.let { "[文件] $it" } ?: "[文件]"
        MessageType.NUDGE -> "[戳一戳]"
        MessageType.REVOKED -> "[消息已撤回]"
        MessageType.SYSTEM, MessageType.SK_DIST -> ""
    }

    /** 写入 cacheDir/exports 下的 txt 文件，返回 File 供分享。失败返回 null。 */
    fun write(context: Context, fileName: String, text: String): File? {
        if (text.isBlank()) return null
        return runCatching {
            val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
            val file = File(dir, "$safe.txt")
            file.writeText(text, Charsets.UTF_8)
            file
        }.getOrNull()
    }

    /** 通过系统分享面板分享导出文件。 */
    fun share(context: Context, file: File, chooserTitle: String): Boolean =
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "聊天记录导出")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
}
