package com.maodouchat.slim

import java.io.File
import java.security.MessageDigest

/**
 * 按需贴纸文件的本地完整性判断。
 *
 * 远程清单中的 SHA-256 是下载文件的唯一可信来源。即使文件名和长度相同，也不能把
 * 旧服务器或旧清单留下的文件直接当作当前文件复用；所有“已缓存”判断都走这里。
 */
internal object StickerFilePolicy {

    fun isCurrent(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return sha256(file) == expectedSha256.lowercase()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
