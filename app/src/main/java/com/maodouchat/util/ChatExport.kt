package com.maodouchat.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.maodouchat.core.model.Message
import com.maodouchat.domain.messaging.ChatExportBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录导出（8.54 / U07）：把本机会话消息渲染为纯文本并分享。
 * 建立明确格式版本、敏感门禁、流式写入与取消机制。
 * 仅导出本地已解密消息；内容不经网络。上限 [MAX_MESSAGES] 条防止超大会话 OOM。
 */
object ChatExport {
    private val headerTimeFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    const val MAX_MESSAGES = 2000
    const val FORMAT_VERSION = ChatExportBody.FORMAT_VERSION

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
    ): String = ChatExportBody.buildText(
        chatName = chatName,
        ownerId = ownerId,
        resolveSenderName = resolveSenderName,
        messages = messages,
        exportedAtText = headerTimeFormat.get().format(Date(exportedAt)),
    )

    /** 写入 cacheDir/exports 下的 txt 文件，返回 File 供分享。失败返回 null。 */
    fun write(context: Context, fileName: String, text: String): File? {
        if (text.isBlank()) return null
        return runCatching {
            val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val safe = ChatExportBody.sanitizeExportFileName(fileName)
            val file = File(dir, "$safe.txt")
            file.writeText(text, Charsets.UTF_8)
            file
        }.getOrNull()
    }

    /**
     * 流式写入 cacheDir/exports 下的 txt 文件。
     * 逐条消息流式写入，避免全量拼接大字符串产生 OOM；
     * 协作式支持 [isCancelled]，中途被取消时自动清理临时文件并返回 null。
     */
    fun writeStream(
        context: Context,
        fileName: String,
        chatName: String,
        ownerId: String,
        messages: Sequence<Message>,
        resolveSenderName: (senderId: String) -> String,
        exportedAt: Long = System.currentTimeMillis(),
        isCancelled: () -> Boolean = { false },
    ): File? {
        val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val safe = ChatExportBody.sanitizeExportFileName(fileName)
        val file = File(dir, "$safe.txt")
        val tempFile = File(dir, "$safe.tmp")

        val exportTs = headerTimeFormat.get().format(Date(exportedAt))

        var cancelled = false
        try {
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("=== ${chatName.ifBlank { "-" }} ===\n")
                writer.write("format_version=$FORMAT_VERSION\n")
                writer.write("exported_at=$exportTs\n\n")

                for (m in messages) {
                    if (isCancelled()) {
                        cancelled = true
                        break
                    }
                    ChatExportBody.formatLine(m, ownerId, resolveSenderName)?.let { writer.write("$it\n") }
                }
            }

            if (cancelled || isCancelled()) {
                tempFile.delete()
                return null
            }

            if (file.exists()) file.delete()
            return if (tempFile.renameTo(file)) {
                file
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                file
            }
        } catch (e: Exception) {
            tempFile.delete()
            return null
        }
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
