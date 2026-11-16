package com.maodouchat.domain.messaging

// 文件消息兜底文件名：从 URI 路径取最后一段；非法 URI 或无文件名返回 null。
object MessageFileNamePolicy {

    fun fileNameFromUri(uri: String): String? = runCatching {
        java.net.URI(uri).path?.substringAfterLast('/')?.takeIf(String::isNotBlank)
    }.getOrNull()
}
