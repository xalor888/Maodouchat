package com.maodouchat.server.service

import java.io.File
import java.util.UUID

// 群头像文件簇 — 保存/URL 解析/删除/过期清理

fun FileStorageService.saveGroupAvatar(base64Data: String, chatId: String): String {
    require(base64Data.length <= MAX_BASE64_CHARS) { "群头像过大" }
    val image = decodeValidatedImage(base64Data, "群头像")
    val safeChatId = chatId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val fileName = "group_${safeChatId}_${UUID.randomUUID().toString().take(8)}.jpg"
    val file = File(requireTypeRoot("group-avatars"), fileName)
    writeJpeg(image, file)
    return "$baseUrl/api/chats/$chatId/avatar/file/$fileName"
}

fun FileStorageService.deleteGroupAvatarUrl(url: String?, expectedChatId: String? = null): Boolean {
    val filename = groupAvatarFilename(url, expectedChatId) ?: return false
    return runCatching { resolveFile("group-avatars", filename)?.delete() == true }.getOrDefault(false)
}

internal fun FileStorageService.groupAvatarFilename(url: String?, expectedChatId: String? = null): String? {
    val prefix = "$baseUrl/api/chats/"
    val relative = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
    val parts = relative.split('/')
    if (parts.size != 4 || parts[1] != "avatar" || parts[2] != "file") return null
    val chatId = parts[0]
    if (expectedChatId != null && chatId != expectedChatId) return null
    val filename = parts[3]
    return filename.takeIf { isOwnedGroupAvatarFilename(it, chatId) }
}

internal fun FileStorageService.isOwnedGroupAvatarFilename(filename: String, chatId: String): Boolean {
    if (chatId.isBlank() || !filename.matches(safeFilenamePattern)) return false
    val safeChatId = chatId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val suffix = filename.removePrefix("group_${safeChatId}_")
    return suffix != filename && suffix.matches(randomImageSuffixPattern)
}

fun FileStorageService.deleteStaleGroupAvatars(validFilenames: Set<String>, olderThan: Long): Int =
    LocalBlobStorage.deleteStale("group-avatars", validFilenames, olderThan, ::isGroupAvatarFilename)

internal fun FileStorageService.isGroupAvatarFilename(filename: String): Boolean =
    filename.matches(safeFilenamePattern) && filename.startsWith("group_") &&
        filename.substringAfterLast('_', "").matches(randomImageSuffixPattern)
