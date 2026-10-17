package com.maodouchat.network.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile

private class FileChunkRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var written = 0L
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (written < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(offset + written, total)
            }
            require(written == length) { "attachment_chunk_source_changed" }
        }
    }
}
