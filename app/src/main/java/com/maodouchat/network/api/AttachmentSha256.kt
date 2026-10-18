package com.maodouchat.network.api

import com.maodouchat.util.toHexString
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal fun File.sha256Hex(): String = sha256Hex(0L, length())

// MessageDigest 非线程安全，ThreadLocal 每线程复用一个；异常路径走不到 digest()，取用前先 reset。
internal val mediaApiSha256Digest: ThreadLocal<MessageDigest> =
    ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

internal fun File.sha256Hex(offset: Long, length: Long): String {
    require(offset >= 0L && length >= 0L && offset + length <= this.length())
    val digest = mediaApiSha256Digest.get().also { it.reset() }
    RandomAccessFile(this, "r").use { input ->
        input.seek(offset)
        var remaining = length
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        require(remaining == 0L) { "attachment_source_changed" }
    }
    return digest.digest().toHexString()
}
