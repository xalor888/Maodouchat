package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

object EncryptedAttachmentStorage {
    sealed interface AppendResult {
        data class Accepted(val uploadedBytes: Long, val replayed: Boolean) : AppendResult
        data class OffsetMismatch(val uploadedBytes: Long) : AppendResult
        data object ContentMismatch : AppendResult
    }

    private val storageRoot = File(ServerConfig.storageDir).canonicalFile
    private val root = File(storageRoot, "encrypted-attachments").apply {
        require(isDirectory || mkdirs()) { "附件存储目录创建失败" }
    }.canonicalFile.also {
        require(it.parentFile == storageRoot) { "附件存储目录路径非法" }
    }
    private val idPattern = Regex("^att_[A-Za-z0-9_-]{20,100}$")
    private val idLocks = Array(64) { Any() }

    fun createTempFile(id: String): File {
        require(idPattern.matches(id)) { "附件 ID 无效" }
        return checkedFile("$id.part")
    }

    fun finalizeUpload(id: String, tempFile: File): File {
        require(idPattern.matches(id)) { "附件 ID 无效" }
        return withIdLock(id) {
            val expectedTemp = checkedFile("$id.part").canonicalFile
            require(tempFile.canonicalFile == expectedTemp) { "附件临时文件无效" }
            finalizeUploadLocked(id, expectedTemp)
        }
    }

    fun appendChunk(id: String, offset: Long, bytes: ByteArray, maxSize: Long): AppendResult {
        require(idPattern.matches(id))
        return withIdLock(id) {
            resolveUnlocked(id)?.let { return@withIdLock AppendResult.OffsetMismatch(it.length()) }
            val target = checkedFile("$id.part")
            RandomAccessFile(target, "rw").use { file ->
                file.channel.lock().use {
                    val current = file.length()
                    val chunkSize = bytes.size.toLong()
                    if (offset < 0L || offset > current) return@withIdLock AppendResult.OffsetMismatch(current)
                    if (offset < current) {
                        if (chunkSize > current - offset) return@withIdLock AppendResult.OffsetMismatch(current)
                        val existing = ByteArray(bytes.size)
                        file.seek(offset)
                        file.readFully(existing)
                        return@withIdLock if (existing.contentEquals(bytes)) {
                            AppendResult.Accepted(current, replayed = true)
                        } else {
                            AppendResult.ContentMismatch
                        }
                    }
                    if (maxSize < current || chunkSize > maxSize - current) {
                        return@withIdLock AppendResult.OffsetMismatch(current)
                    }
                    file.seek(current)
                    file.write(bytes)
                    file.fd.sync()
                    AppendResult.Accepted(file.length(), replayed = false)
                }
            }
        }
    }

    fun uploadedBytes(id: String): Long? {
        if (!idPattern.matches(id)) return null
        return withIdLock(id) {
            val finalFile = checkedFile("$id.bin")
            val tempFile = checkedFile("$id.part")
            when {
                finalFile.isFile -> finalFile.length()
                tempFile.isFile -> tempFile.length()
                else -> 0L
            }
        }
    }

    fun sha256(id: String): String? {
        if (!idPattern.matches(id)) return null
        return withIdLock(id) {
            val file = checkedFile("$id.bin").takeIf { it.isFile }
                ?: checkedFile("$id.part").takeIf { it.isFile }
                ?: return@withIdLock null
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun finalizeResumableUpload(id: String): File? {
        if (!idPattern.matches(id)) return null
        return withIdLock(id) {
            resolveUnlocked(id)?.let { return@withIdLock it }
            val temp = checkedFile("$id.part").takeIf { it.isFile } ?: return@withIdLock null
            finalizeUploadLocked(id, temp)
        }
    }

    fun resolve(id: String): File? {
        if (!idPattern.matches(id)) return null
        return withIdLock(id) { resolveUnlocked(id) }
    }

    fun delete(id: String): Boolean {
        if (!idPattern.matches(id)) return false
        return withIdLock(id) {
            val finalDeleted = checkedFile("$id.bin").let { !it.exists() || it.delete() }
            val tempDeleted = checkedFile("$id.part").let { !it.exists() || it.delete() }
            finalDeleted && tempDeleted
        }
    }

    fun deleteStaleFiles(validIds: Set<String>, olderThan: Long): Int {
        return root.listFiles().orEmpty().count { file ->
            if (!file.isFile || file.lastModified() > olderThan) return@count false
            val id = when {
                file.name.endsWith(".part") -> file.name.removeSuffix(".part")
                file.name.endsWith(".bin") -> file.name.removeSuffix(".bin")
                else -> return@count false
            }
            if (!idPattern.matches(id)) return@count false
            withIdLock(id) {
                file.isFile && file.lastModified() <= olderThan && id !in validIds && file.delete()
            }
        }
    }

    private fun finalizeUploadLocked(id: String, tempFile: File): File {
        val target = checkedFile("$id.bin")
        // 一次性 POST 在磁盘 move 成功、调用方后续失败后重试：.bin 已在、.part 已无。
        // 此前 require(!target.exists()) / require(tempFile.isFile) 会把幂等重试打成 500。
        if (target.isFile && !tempFile.isFile) return target
        require(tempFile.isFile) { "附件临时文件不存在" }
        require(!target.exists()) { "附件已完成" }
        try {
            Files.move(tempFile.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), target.toPath())
        }
        return target
    }

    private fun resolveUnlocked(id: String): File? = checkedFile("$id.bin").takeIf { it.isFile }

    private fun <T> withIdLock(id: String, block: () -> T): T {
        val lock = idLocks[(id.hashCode() and Int.MAX_VALUE) % idLocks.size]
        return synchronized(lock, block)
    }

    private fun checkedFile(name: String): File {
        val file = File(root, name).canonicalFile
        require(file.parentFile == root) { "附件路径非法" }
        return file
    }
}
