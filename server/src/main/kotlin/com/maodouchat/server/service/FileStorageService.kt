package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.io.File

/**
 * 文件存储服务 — 头像等文件的本地存储
 */
object FileStorageService {

    internal const val MAX_BASE64_CHARS = 2_800_000
    internal const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    internal const val MAX_DIMENSION = 4096

    /** Allowed MIME types for image uploads (mirrors magic-number allow-list below). */
    internal val ALLOWED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    /** Allowed file extensions for avatar/post images. */
    internal val ALLOWED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

    internal val storageRoot = BlobRoot.storageRoot
    internal val baseUrl = ServerConfig.baseUrl.trimEnd('/')
    internal val safeFilenamePattern = Regex("^[A-Za-z0-9_.-]+$")
    internal val randomImageSuffixPattern = Regex("^[A-Fa-f0-9]{8}\\.jpg$")
    internal val storageTypes = setOf("avatars", "posts", "group-avatars")

    init {
        require(storageRoot.isDirectory || storageRoot.mkdirs()) { "存储目录创建失败" }
        storageTypes.forEach { type ->
            val directory = File(storageRoot, type)
            require(directory.isDirectory || directory.mkdirs()) { "存储子目录创建失败: $type" }
            require(directory.canonicalFile.parentFile == storageRoot) { "存储子目录路径非法: $type" }
        }
    }

    internal const val MAX_IMAGE_PIXELS = 16_000_000L

}
