package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 文件存储服务 — 头像等文件的本地存储
 */
object FileStorageService {

    private const val MAX_BASE64_CHARS = 2_800_000
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_DIMENSION = 4096

    /** Allowed MIME types for image uploads (mirrors magic-number allow-list below). */
    private val ALLOWED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    /** Allowed file extensions for avatar/post images. */
    private val ALLOWED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

    private val storageDir = ServerConfig.storageDir
    private val storageRoot = File(storageDir).canonicalFile
    private val baseUrl = ServerConfig.baseUrl.trimEnd('/')
    private val safeFilenamePattern = Regex("^[A-Za-z0-9_.-]+$")
    private val storageTypes = setOf("avatars", "posts", "group-avatars")

    init {
        require(storageRoot.isDirectory || storageRoot.mkdirs()) { "存储目录创建失败" }
        storageTypes.forEach { type ->
            val directory = File(storageRoot, type)
            require(directory.isDirectory || directory.mkdirs()) { "存储子目录创建失败: $type" }
            require(directory.canonicalFile.parentFile == storageRoot) { "存储子目录路径非法: $type" }
        }
    }

    /**
     * 保存 Base64 编码的头像图片
     *
     * @param base64Data Base64 编码的图片数据
     * @param userId 用户 ID
     * @return 头像的访问 URL
     */
    fun saveAvatar(base64Data: String, userId: String): String {
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

    fun avatarUrl(filename: String): String? {
        if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) return null
        return "$baseUrl/api/files/avatar/$filename"
    }

    fun deleteAvatarUrl(url: String?, userId: String): Boolean {
        val prefix = "$baseUrl/api/files/avatar/"
        val filename = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return false
        if ('/' in filename || !isOwnedAvatarFilename(filename, userId)) return false
        return runCatching { resolveFile("avatars", filename)?.delete() == true }.getOrDefault(false)
    }

    internal fun isOwnedAvatarFilename(filename: String, userId: String): Boolean {
        if (userId.isBlank()) return false
        val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
        val suffix = filename.removePrefix("avatar_${safeUserId}_")
        return suffix != filename && suffix.matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))
    }

    fun saveGroupAvatar(base64Data: String, chatId: String): String {
        require(base64Data.length <= MAX_BASE64_CHARS) { "群头像过大" }
        val image = decodeValidatedImage(base64Data, "群头像")
        val safeChatId = chatId.replace("/", "_").replace("\\", "_").replace("..", "_")
        val fileName = "group_${safeChatId}_${UUID.randomUUID().toString().take(8)}.jpg"
        val file = File(requireTypeRoot("group-avatars"), fileName)
        writeJpeg(image, file)
        return "$baseUrl/api/chats/$chatId/avatar/file/$fileName"
    }

    /**
     * 保存 Base64 编码的动态图片。
     */
    fun savePostImage(base64Data: String, userId: String): String {
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

    fun isOwnedPostImageUrl(url: String, userId: String): Boolean {
        val prefix = "$baseUrl/api/files/post-image/"
        if (!url.startsWith(prefix)) return false
        val filename = url.removePrefix(prefix)
        if ('/' in filename || !isOwnedPostImageFilename(filename, userId)) return false
        return resolveFile("posts", filename)?.isFile == true
    }

    internal fun isOwnedPostImageFilename(filename: String, userId: String): Boolean {
        if (userId.isBlank()) return false
        val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
        val suffix = filename.removePrefix("post_${safeUserId}_")
        return suffix != filename && suffix.matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))
    }

    /**
     * 获取文件的本地路径
     */
    fun getFilePath(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val rootPath = storageRoot.toPath()
        val resolved = resolveRelativePath(rootPath, relativePath) ?: return null
        if (!Files.exists(resolved)) return null
        return runCatching {
            val realRoot = rootPath.toRealPath()
            val realFile = resolved.toRealPath()
            realFile.takeIf { it.startsWith(realRoot) }?.toFile()
        }.getOrNull()
    }

    internal fun resolveRelativePath(root: Path, relativePath: String): Path? = runCatching {
        val requestedPath = Paths.get(relativePath)
        if (requestedPath.isAbsolute) return@runCatching null
        val normalizedRoot = root.toAbsolutePath().normalize()
        normalizedRoot.resolve(requestedPath).normalize().takeIf { it.startsWith(normalizedRoot) }
    }.getOrNull()

    /**
     * 根据 type + filename 解析本地文件 — 供 /api/files 路由使用。
     * 返回 null 表示路径非法或文件不存在。
     */
    fun resolveFile(type: String, filename: String): File? {
        if (!filename.matches(safeFilenamePattern)) return null
        return runCatching {
            val typeRoot = resolveTypeRoot(type) ?: return@runCatching null
            File(typeRoot, filename).canonicalFile.takeIf { it.parentFile == typeRoot }
        }.getOrNull()
    }

    private fun decodeValidatedImage(base64Data: String, label: String): BufferedImage {
        val normalized = base64Data.substringAfter(",", base64Data)
        val maxChars = ServerConfig.maxBase64ImageChars
        require(normalized.length <= maxChars) { "${label}数据过大，最大 ${maxChars / 1_000_000}MB" }
        val imageBytes = try {
            Base64.getDecoder().decode(normalized)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("${label}数据格式无效")
        }

        val maxBytes = ServerConfig.maxImageBytes
        require(imageBytes.size in 1..maxBytes) { "${label}文件过大，最大 ${maxBytes / 1024 / 1024}MB" }
        require(hasSupportedImageHeader(imageBytes)) { "仅支持 JPG、PNG、GIF 或 WebP 图片" }

        return decodeImage(imageBytes, label)
    }

    private fun decodeImage(bytes: ByteArray, label: String): BufferedImage {
        val maxDim = ServerConfig.maxImageDimension
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("${label}无法解析")
        stream.use { iis ->
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) throw IllegalArgumentException("${label}无法解析")
            val reader = readers.next()
            reader.input = iis
            try {
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..maxDim && height in 1..maxDim) { "${label}尺寸过大，最大 ${maxDim}px" }
                // 像素总数上限：防超大分辨率（如 4096×4096=16.7M 像素 ≈ 67MB 堆）逐请求打爆内存
                require(1L * width * height <= MAX_IMAGE_PIXELS) { "${label}像素总数过大" }
                return reader.read(0) ?: throw IllegalArgumentException("${label}无法解析")
            } finally {
                reader.dispose()
            }
        }
    }

    private const val MAX_IMAGE_PIXELS = 16_000_000L

    fun deleteGroupAvatarUrl(url: String?, expectedChatId: String? = null): Boolean {
        val filename = groupAvatarFilename(url, expectedChatId) ?: return false
        return runCatching { resolveFile("group-avatars", filename)?.delete() == true }.getOrDefault(false)
    }

    internal fun groupAvatarFilename(url: String?, expectedChatId: String? = null): String? {
        val prefix = "$baseUrl/api/chats/"
        val relative = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
        val parts = relative.split('/')
        if (parts.size != 4 || parts[1] != "avatar" || parts[2] != "file") return null
        val chatId = parts[0]
        if (expectedChatId != null && chatId != expectedChatId) return null
        val filename = parts[3]
        return filename.takeIf { isOwnedGroupAvatarFilename(it, chatId) }
    }

    internal fun isOwnedGroupAvatarFilename(filename: String, chatId: String): Boolean {
        if (chatId.isBlank() || !filename.matches(safeFilenamePattern)) return false
        val safeChatId = chatId.replace("/", "_").replace("\\", "_").replace("..", "_")
        val suffix = filename.removePrefix("group_${safeChatId}_")
        return suffix != filename && suffix.matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))
    }

    fun deletePostImage(filename: String): Boolean {
        if (!isPostImageFilename(filename)) return false
        return runCatching { resolveFile("posts", filename)?.delete() == true }.getOrDefault(false)
    }

    fun deletePostImagesForUser(userId: String): Int {
        if (userId.isBlank()) return 0
        return resolveTypeRoot("posts")?.listFiles().orEmpty().count { file ->
            file.isFile && isOwnedPostImageFilename(file.name, userId) && file.delete()
        }
    }

    fun deleteStalePostImages(validFilenames: Set<String>, olderThan: Long): Int =
        deleteStaleImages("posts", validFilenames, olderThan, ::isPostImageFilename)

    /** 列出已超过保留期、且文件名符合动态图片格式的候选文件（不删除）。 */
    fun listStalePostImageFiles(olderThan: Long): List<String> =
        resolveTypeRoot("posts")?.listFiles().orEmpty()
            .filter { file -> file.isFile && file.lastModified() <= olderThan && isPostImageFilename(file.name) }
            .map { it.name }

    fun deleteStaleGroupAvatars(validFilenames: Set<String>, olderThan: Long): Int =
        deleteStaleImages("group-avatars", validFilenames, olderThan, ::isGroupAvatarFilename)

    private fun deleteStaleImages(
        type: String,
        validFilenames: Set<String>,
        olderThan: Long,
        acceptsFilename: (String) -> Boolean
    ): Int {
        return resolveTypeRoot(type)?.listFiles().orEmpty().count { file ->
            file.isFile &&
                file.lastModified() <= olderThan &&
                file.name !in validFilenames &&
                acceptsFilename(file.name) &&
                file.delete()
        }
    }

    private fun isPostImageFilename(filename: String): Boolean =
        filename.matches(safeFilenamePattern) && filename.startsWith("post_") &&
            filename.substringAfterLast('_', "").matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))

    private fun isGroupAvatarFilename(filename: String): Boolean =
        filename.matches(safeFilenamePattern) && filename.startsWith("group_") &&
            filename.substringAfterLast('_', "").matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))

    private fun requireTypeRoot(type: String): File =
        requireNotNull(resolveTypeRoot(type)) { "存储子目录路径非法: $type" }

    private fun resolveTypeRoot(type: String): File? {
        if (type !in storageTypes) return null
        return runCatching {
            File(storageRoot, type).canonicalFile.takeIf { it.parentFile == storageRoot && it.isDirectory }
        }.getOrNull()
    }

    private fun writeJpeg(image: BufferedImage, file: File) {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        var tempFile: File? = null
        try {
            val createdTempFile = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
            tempFile = createdTempFile
            require(ImageIO.write(rgb, "jpg", createdTempFile) && createdTempFile.isFile && createdTempFile.length() > 0L) { "图片保存失败" }
            require(!file.exists()) { "图片文件名冲突" }
            try {
                Files.move(createdTempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(createdTempFile.toPath(), file.toPath())
            }
        } finally {
            rgb.flush()
            image.flush()
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun hasSupportedImageHeader(bytes: ByteArray): Boolean {
        val jpg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val gif = bytes.size >= 6 && (bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" || bytes.copyOfRange(0, 6).decodeToString() == "GIF89a")
        val webp = bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        return jpg || png || gif || webp
    }
}
