package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.domain.messaging.isDecryptable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr197b(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr197b(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt197b(s: String?): String = if (s == null) "NULL" else "S:" + encStr197b(s)

// MessageType.isDecryptable 的独立重实现：内容型 9 类可解密，控制型不可解密。
private val DECRYPTABLE197b = setOf(
    MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
    MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
    MessageType.VOICE, MessageType.FILE,
)

private fun decryptableOracle197b(type: MessageType): Boolean = type in DECRYPTABLE197b

// ChatItem.timelineContentType 的独立重实现：分隔线固定串，消息按 type 名。
private fun contentTypeOracle197b(variant: Int, type: MessageType): String = when (variant) {
    0 -> "date_separator"
    1 -> "message_${type.name}"
    else -> "unread_separator"
}

private fun decryptableProd197b(type: MessageType): Boolean = type.isDecryptable()

private fun contentTypeProd197b(variant: Int, type: MessageType): String = when (variant) {
    0 -> ChatItem.DateSeparator("d", "k").timelineContentType()
    1 -> ChatItem.Msg(
        Message(id = "m", chatId = "c", senderId = "s", content = "x", type = type),
        showAvatar = true
    ).timelineContentType()
    else -> ChatItem.UnreadSeparator("m").timelineContentType()
}

// op: dec | ctype。dec 用 n1=类型下标；ctype 用 n1=变体（0 日期分隔线/1 消息/2 未读分隔线）、n2=类型下标。
private data class TypeCase197b(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun typeOracle197b(c: TypeCase197b): String = when (c.op) {
    "dec" -> decryptableOracle197b(MessageType.entries[c.n1]).toString()
    "ctype" -> contentTypeOracle197b(c.n1, MessageType.entries[c.n2])
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun typeProd197b(c: TypeCase197b): String = when (c.op) {
    "dec" -> decryptableProd197b(MessageType.entries[c.n1]).toString()
    "ctype" -> contentTypeProd197b(c.n1, MessageType.entries[c.n2])
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun randomTypeCase197b(r: Random): TypeCase197b = when (r.nextInt(2)) {
    0 -> TypeCase197b("dec", n1 = r.nextInt(MessageType.entries.size))
    else -> TypeCase197b(
        "ctype",
        n1 = r.nextInt(3),
        n2 = r.nextInt(MessageType.entries.size)
    )
}

private fun boundaryTypeCase197b(r: Random): TypeCase197b = when (r.nextInt(2)) {
    // 边界腿偏重两端类型与三变体全覆盖。
    0 -> TypeCase197b(
        "dec",
        n1 = listOf(0, 8, 9, 12, MessageType.entries.size - 1)[r.nextInt(5)]
    )
    else -> TypeCase197b(
        "ctype",
        n1 = r.nextInt(3),
        n2 = listOf(0, 8, 9, 12, MessageType.entries.size - 1)[r.nextInt(5)]
    )
}

// ---------- tests ----------

class ChatTimelineTypeFuzzTest {

    @Test
    fun messageTypeDifferential197() {
        val r = Random(197201)
        repeat(300) {
            val c = randomTypeCase197b(r)
            assertEquals(typeOracle197b(c), typeProd197b(c))
        }
    }

    @Test
    fun messageTypeBoundaryDifferential197() {
        val r = Random(197202)
        repeat(300) {
            val c = boundaryTypeCase197b(r)
            assertEquals(typeOracle197b(c), typeProd197b(c))
        }
    }

    @Test
    fun messageTypePinned197() {
        // isDecryptable：内容型 9 类可解密，控制型 4 类不可解密。
        for (type in MessageType.entries) {
            assertEquals(type in DECRYPTABLE197b, decryptableProd197b(type))
        }
        assertEquals(true, decryptableProd197b(MessageType.TEXT))
        assertEquals(true, decryptableProd197b(MessageType.FILE))
        assertEquals(false, decryptableProd197b(MessageType.NUDGE))
        assertEquals(false, decryptableProd197b(MessageType.SK_DIST))
        assertEquals(false, decryptableProd197b(MessageType.SYSTEM))
        assertEquals(false, decryptableProd197b(MessageType.REVOKED))
        // timelineContentType：分隔线固定串，消息按 type 名。
        assertEquals("date_separator", contentTypeProd197b(0, MessageType.TEXT))
        assertEquals("unread_separator", contentTypeProd197b(2, MessageType.TEXT))
        for (type in MessageType.entries) {
            assertEquals("message_${type.name}", contentTypeProd197b(1, type))
        }
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            TypeCase197b("dec", n1 = 0),
            TypeCase197b("dec", n1 = 9),
            TypeCase197b("dec", n1 = 12),
            TypeCase197b("ctype", n1 = 0, n2 = 0),
            TypeCase197b("ctype", n1 = 1, n2 = 5),
            TypeCase197b("ctype", n1 = 2, n2 = 12),
        )) assertEquals(typeOracle197b(c), typeProd197b(c))
    }
}
