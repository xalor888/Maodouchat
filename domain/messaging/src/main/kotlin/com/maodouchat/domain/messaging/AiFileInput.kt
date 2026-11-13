package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.PreparedAiFile
import java.util.Base64

// AI 文件输入解析：附件内容 + base64 → 规范化文件名与 MIME。自 app 侧
// ChatDetailAiMediaAnalysis.kt 的 ChatDetailViewModel.resolveAiFileInput 逐字平移，
// 仅两处平台适配：android.util.Base64 改纯 JVM 的 java.util.Base64；
// androidx 的 Uri.lastPathSegment 改纯字符串取最后一段并去掉 ?/# 后缀
//（百分号解码的边角语义略有差异，仅影响无 storedName 时的回退候选名）。
fun resolveAiFileInput(message: Message, base64: String): PreparedAiFile? {
    val metadata = message.parsedMeta()
    val storedName = metadata.fileName?.trim()?.takeIf(String::isNotBlank)
    val cachedName = message.parsedContent()
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .takeIf(String::isNotBlank)
    val candidateName = storedName ?: cachedName ?: "document"
    val extension = candidateName.substringAfterLast('.', "").lowercase()
    val canonicalMime = when (extension) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        else -> null
    }
    if (canonicalMime != null) return PreparedAiFile(base64, candidateName.take(120), canonicalMime)

    val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
    if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
        return PreparedAiFile(base64, "document.pdf", "application/pdf")
    }
    val textContent = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
    if (textContent.isBlank() || textContent.length > 120_000 || '\u0000' in textContent || '\uFFFD' in textContent) return null
    return PreparedAiFile(base64, "document.txt", "text/plain")
}
