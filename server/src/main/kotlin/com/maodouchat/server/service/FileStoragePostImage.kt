package com.maodouchat.server.service

import java.io.File
import java.util.UUID

// 动态图片文件簇 — 保存/归属校验/删除/过期清理

/**
 * 保存 Base64 编码的动态图片。
 */
fun FileStorageService.savePostImage(base64Data: String, userId: String): String {
    require(base64Data.length <= MAX_BASE64_CHARS) { "图片过大" }

    val image = decodeValidatedImage(base64Data, "图片")

    // 安全：userId 来自 JWT subject（用户可控），需剥离路径分隔符防止路径遍历
    val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val fileName = "post_${safeUserId}_${UUID.randomUUID().toString().take(8)}.jpg"
    val file = File(requireTypeRoot("posts"), fileName)
    writeJpeg(image, file)

    // 走 /api/files/post-image/{filename} 认证路由
    // postId 在 post 创建时通过 registerPostImage 注册到 filename→postId 映射
    return "$baseUrl/api/files/post-image/$fileName"
}

fun FileStorageService.isOwnedPostImageUrl(url: String, userId: String): Boolean {
    val prefix = "$baseUrl/api/files/post-image/"
    if (!url.startsWith(prefix)) return false
    val filename = url.removePrefix(prefix)
    if ('/' in filename || !isOwnedPostImageFilename(filename, userId)) return false
    return resolveFile("posts", filename)?.isFile == true
}

internal fun FileStorageService.isOwnedPostImageFilename(filename: String, userId: String): Boolean {
    if (userId.isBlank()) return false
    val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val suffix = filename.removePrefix("post_${safeUserId}_")
    return suffix != filename && suffix.matches(randomImageSuffixPattern)
}

fun FileStorageService.deletePostImage(filename: String): Boolean {
    if (!isPostImageFilename(filename)) return false
    return runCatching { resolveFile("posts", filename)?.delete() == true }.getOrDefault(false)
}

fun FileStorageService.deletePostImagesForUser(userId: String): Int {
    if (userId.isBlank()) return 0
    return resolveTypeRoot("posts")?.listFiles().orEmpty().count { file ->
        file.isFile && isOwnedPostImageFilename(file.name, userId) && file.delete()
    }
}

fun FileStorageService.deleteStalePostImages(validFilenames: Set<String>, olderThan: Long): Int =
    LocalBlobStorage.deleteStale("posts", validFilenames, olderThan, ::isPostImageFilename)

/** 列出已超过保留期、且文件名符合动态图片格式的候选文件（不删除）。 */
fun FileStorageService.listStalePostImageFiles(olderThan: Long): List<String> =
    resolveTypeRoot("posts")?.listFiles().orEmpty()
        .filter { file -> file.isFile && file.lastModified() <= olderThan && isPostImageFilename(file.name) }
        .map { it.name }

internal fun FileStorageService.isPostImageFilename(filename: String): Boolean =
    filename.matches(safeFilenamePattern) && filename.startsWith("post_") &&
        filename.substringAfterLast('_', "").matches(randomImageSuffixPattern)
