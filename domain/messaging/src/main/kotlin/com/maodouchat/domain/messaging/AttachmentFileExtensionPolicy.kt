package com.maodouchat.domain.messaging

// 附件缓存文件名后缀：取原文件名的合法扩展名（小写字母数字 1–10 位），否则回落 .bin。
object AttachmentFileExtensionPolicy {

    private val extensionNameRegex = Regex("[a-z0-9]{1,10}")

    fun fileExtension(originalFileName: String?): String =
        originalFileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.matches(extensionNameRegex) }
            ?.let { ".$it" }
            ?: ".bin"
}
