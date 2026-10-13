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
import javax.imageio.ImageIO

// 共享内核 — 路径解析/图片解码/写盘/魔数校验

/**
 * 获取文件的本地路径
 */
fun FileStorageService.getFilePath(relativePath: String): File? {
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

internal fun FileStorageService.resolveRelativePath(root: Path, relativePath: String): Path? = runCatching {
    val requestedPath = Paths.get(relativePath)
    if (requestedPath.isAbsolute) return@runCatching null
    val normalizedRoot = root.toAbsolutePath().normalize()
    normalizedRoot.resolve(requestedPath).normalize().takeIf { it.startsWith(normalizedRoot) }
}.getOrNull()

/**
 * 根据 type + filename 解析本地文件 — 供 /api/files 路由使用。
 * 返回 null 表示路径非法或文件不存在。
 */
fun FileStorageService.resolveFile(type: String, filename: String): File? {
    if (!filename.matches(safeFilenamePattern)) return null
    return LocalBlobStorage.resolve(type, filename)
}

internal fun FileStorageService.decodeValidatedImage(base64Data: String, label: String): BufferedImage {
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

internal fun FileStorageService.decodeImage(bytes: ByteArray, label: String): BufferedImage {
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

internal fun FileStorageService.writeJpeg(image: BufferedImage, file: File) {
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

internal fun FileStorageService.hasSupportedImageHeader(bytes: ByteArray): Boolean {
    val jpg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    val gif = bytes.size >= 6 && (bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" || bytes.copyOfRange(0, 6).decodeToString() == "GIF89a")
    val webp = bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    return jpg || png || gif || webp
}

internal fun FileStorageService.requireTypeRoot(type: String): File =
    requireNotNull(resolveTypeRoot(type)) { "存储子目录路径非法: $type" }

internal fun FileStorageService.resolveTypeRoot(type: String): File? {
    if (type !in storageTypes) return null
    return BlobRoot.resolve(storageRoot, type)?.takeIf { it.isDirectory }
}
