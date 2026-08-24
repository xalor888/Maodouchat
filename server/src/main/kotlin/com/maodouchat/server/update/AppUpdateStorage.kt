package com.maodouchat.server.update

import com.maodouchat.server.config.ServerConfig
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AppUpdateStorage {
    private const val DIR = "app-updates"
    const val FILE_NAME = "latest.apk"

    fun latestFile(): File = File(typeRoot(), FILE_NAME)

    fun saveFromStream(input: InputStream, maxBytes: Long = AppUpdatePublishPolicy.MAX_APK_BYTES): File {
        val dir = typeRoot()
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching { if (tmp.exists()) tmp.delete() }
        var total = 0L
        val magic = ByteArray(4)
        var magicRead = 0
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (magicRead < 4) {
                    val copy = minOf(4 - magicRead, n)
                    System.arraycopy(buf, 0, magic, magicRead, copy)
                    magicRead += copy
                }
                total += n
                if (total > maxBytes) {
                    tmp.delete()
                    error("too_large")
                }
                out.write(buf, 0, n)
            }
            out.flush()
        }
        if (magicRead < 4 || !AppUpdatePublishPolicy.isZipMagic(magic)) {
            tmp.delete()
            error("not_apk")
        }
        if (total < AppUpdatePublishPolicy.MIN_APK_BYTES) {
            tmp.delete()
            error("too_small")
        }
        val dest = latestFile()
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return dest
    }

    private fun typeRoot(): File {
        val root = File(ServerConfig.storageDir).canonicalFile
        require(root.isDirectory || root.mkdirs()) { "storage root missing" }
        val dir = File(root, DIR)
        require(dir.isDirectory || dir.mkdirs()) { "app-updates dir missing" }
        require(dir.canonicalFile.parentFile == root) { "app-updates path illegal" }
        return dir.canonicalFile
    }
}
