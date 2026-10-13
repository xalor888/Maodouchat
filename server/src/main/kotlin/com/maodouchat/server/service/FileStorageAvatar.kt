package com.maodouchat.server.service

import java.io.File
import java.util.UUID

// 用户头像文件簇 — 保存/URL/删除/归属校验

/**
 * 保存 Base64 编码的头像图片
 *
 * @param base64Data Base64 编码的图片数据
 * @param userId 用户 ID
 * @return 头像的访问 URL
 */
fun FileStorageService.saveAvatar(base64Data: String, userId: String): String {
    require(base64Data.length <= MAX_BASE64_CHARS) { "头像过大" }

    val image = decodeValidatedImage(base64Data, "头像")

    // 安全：userId 来自 JWT subject（用户可控），需剥离路径分隔符防止路径遍历
    val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val fileName = "avatar_${safeUserId}_${UUID.randomUUID().toString().take(8)}.jpg"
    val file = File(requireTypeRoot("avatars"), fileName)
    writeJpeg(image, file)

    // 走 /api/files/avatar/{filename} 认证路由，不再暴露 /uploads 静态目录
    return "$baseUrl/api/files/avatar/$fileName"
}

fun FileStorageService.avatarUrl(filename: String): String? {
    if (!filename.matches(safeFilenamePattern)) return null
    return "$baseUrl/api/files/avatar/$filename"
}

fun FileStorageService.deleteAvatarUrl(url: String?, userId: String): Boolean {
    val prefix = "$baseUrl/api/files/avatar/"
    val filename = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return false
    if ('/' in filename || !isOwnedAvatarFilename(filename, userId)) return false
    return runCatching { resolveFile("avatars", filename)?.delete() == true }.getOrDefault(false)
}

internal fun FileStorageService.isOwnedAvatarFilename(filename: String, userId: String): Boolean {
    if (userId.isBlank()) return false
    val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val suffix = filename.removePrefix("avatar_${safeUserId}_")
    return suffix != filename && suffix.matches(randomImageSuffixPattern)
}
