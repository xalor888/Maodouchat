package com.maodouchat.domain.messaging

// 本机模型文件预处理纯规则：base64 归一化、PDF/文本判定、文本有效性与截断、
// 默认文件名与 MIME；解码、PDF 渲染与磁盘读写仍在 app 侧。
object LocalAiFilePrepPolicy {
    const val MAX_TEXT_CHARS = 12_000

    fun normalizeBase64(input: String): String =
        input.replace('-', '+').replace('_', '/')

    fun isPdf(mimeType: String, fileName: String, bytes: ByteArray): Boolean =
        mimeType.lowercase() == "application/pdf" ||
            fileName.lowercase().endsWith(".pdf") ||
            (bytes.size >= 5 && bytes.copyOfRange(0, 5)
                .contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))

    fun isTextMime(mimeType: String): Boolean {
        val mime = mimeType.lowercase()
        return mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "text/markdown" ||
            mime == "text/csv"
    }

    fun isUsableText(decoded: String): Boolean =
        decoded.isNotBlank() && '\u0000' !in decoded && '\uFFFD' !in decoded

    fun truncateText(text: String): String = text.take(MAX_TEXT_CHARS)

    fun defaultPdfFileName(fileName: String): String = fileName.ifBlank { "document.pdf" }

    fun defaultTextFileName(fileName: String): String = fileName.ifBlank { "document.txt" }

    fun defaultTextMime(mimeType: String): String =
        mimeType.lowercase().ifBlank { "text/plain" }
}
