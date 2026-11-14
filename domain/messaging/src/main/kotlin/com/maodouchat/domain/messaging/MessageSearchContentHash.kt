package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import java.security.MessageDigest

/** 搜索文档内容哈希与可索引类型（原 app 侧 MessageSearchRepository 私有逻辑，逐字平移）。 */
object MessageSearchContentHash {

    // 可建索引的消息类型；SYSTEM/REVOKED/SK_DIST 不进索引。
    val SEARCHABLE_TYPES = setOf(
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.VOICE,
        MessageType.LOCATION,
        MessageType.NUDGE,
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.STICKER,
        MessageType.VIDEO,
        MessageType.FILE
    )

    fun contentHash(chatId: String, senderId: String, timestamp: Long, text: String): String {
        val bytes = "$chatId\u0000$senderId\u0000$timestamp\u0000$text".toByteArray(Charsets.UTF_8)
        return sha256Digest.get().digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // MessageDigest 非线程安全，ThreadLocal 每线程复用一个实例；每次使用都以 digest() 收尾（按规范自动重置），与 new 等价。
    private val sha256Digest: ThreadLocal<MessageDigest> =
        ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
}
