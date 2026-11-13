package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MediaCenterCategory
import com.maodouchat.core.model.MediaCenterItem

// 媒体中心搜索匹配：文件名/MIME/正文/链接/位置标签五路忽略大小写匹配。自 app 侧 MediaCenterCategoryContent.kt 逐字平移。
fun mediaCenterItemMatches(item: MediaCenterItem, query: String): Boolean {
    val message = item.message
    val meta = message.parsedMeta()
    val fileName = meta.fileName.orEmpty()
    val mime = meta.fileMimeType.orEmpty()
    val link = item.linkUrl.orEmpty()
    val locationLabel = runCatching {
        val payload = message.parsedLocation()
        payload?.label.orEmpty()
    }.getOrDefault("")
    val content = when (item.category) {
        MediaCenterCategory.LINKS -> link
        MediaCenterCategory.LOCATION -> locationLabel.ifBlank { message.parsedContent() }
        MediaCenterCategory.FILES -> fileName.ifBlank { message.parsedContent() }
        else -> listOf(fileName, mime, message.parsedContent()).joinToString(" ")
    }
    return content.contains(query, ignoreCase = true) ||
        fileName.contains(query, ignoreCase = true) ||
        link.contains(query, ignoreCase = true) ||
        mime.contains(query, ignoreCase = true) ||
        locationLabel.contains(query, ignoreCase = true)
}
