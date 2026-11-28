package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr197c(s: String): String {
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

private fun decStr197c(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt197c(s: String?): String = if (s == null) "NULL" else "S:" + encStr197c(s)

// pushKeyForUser 的独立重实现：不用 javax.crypto.Mac，手写 HMAC-SHA256
//（MessageDigest + ipad/opad），密钥为空时与生产一样返回空串。
private fun hmacSha256Hex197c(key: ByteArray, msg: ByteArray): String {
    val block = ByteArray(64)
    val k = if (key.size > 64) MessageDigest.getInstance("SHA-256").digest(key) else key
    k.copyInto(block)
    val ipad = ByteArray(64) { (block[it].toInt() xor 0x36).toByte() }
    val opad = ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() }
    val inner = MessageDigest.getInstance("SHA-256").run {
        update(ipad)
        update(msg)
        digest()
    }
    val outer = MessageDigest.getInstance("SHA-256").run {
        update(opad)
        update(inner)
        digest()
    }
    return outer.joinToString("") { "%02x".format(it) }
}

private fun pushKeyOracle197c(recipientId: String): String {
    val secret = ServerConfig.pushHmacSecret
    if (secret.isEmpty()) return ""
    return hmacSha256Hex197c(
        secret.toByteArray(Charsets.UTF_8),
        recipientId.toByteArray(Charsets.UTF_8)
    )
}

private fun pushKeyProd197c(recipientId: String): String = pushKeyForUser(recipientId)

// op: key。只用 s1=recipientId。
private data class PushKeyCase197c(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun pushKeyOracle197c(c: PushKeyCase197c): String = when (c.op) {
    "key" -> pushKeyOracle197c(c.s1)
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun pushKeyProd197c(c: PushKeyCase197c): String = when (c.op) {
    "key" -> pushKeyProd197c(c.s1)
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val PUSH_KEY_POOL197c = listOf(
    "", "u1", "user-123", "a", "0", " ", "  ", "a/b", "a\\b", "a:b", "a@b",
    "é", "中文用户", "😀", "a\u0000b", "a\nb", "a\tb", "a\rb",
    "x".repeat(64), "x".repeat(65), "x".repeat(100), "x".repeat(1000),
    "a".repeat(55), "a".repeat(56), "a".repeat(119), "a".repeat(120),
    "\uD800", "a\uD800b", "\uD800\uDC00", "sig", "ts", "sig&ts=1",
    "user_001", "USER", "User", "1234567890", "-1", "+1", "u 1", "u_1",
    "é\ud83d\ude00", "a".repeat(10000).take(1000)
)

private const val PUSH_KEY_ALPHABET197c =
    "abcu0123456789_-/.\\:@ \n\t\r\u0000\u00e9\u4E2D\ud83d\ude00\uD800&=%+sig"

private fun randomPushKeyString197c(r: Random): String {
    if (r.nextInt(4) == 0) return PUSH_KEY_POOL197c[r.nextInt(PUSH_KEY_POOL197c.size)]
    val n = r.nextInt(0, 80)
    return buildString { repeat(n) { append(PUSH_KEY_ALPHABET197c[r.nextInt(PUSH_KEY_ALPHABET197c.length)]) } }
}

private fun randomPushKeyCase197c(r: Random): PushKeyCase197c =
    PushKeyCase197c("key", randomPushKeyString197c(r))

private fun boundaryPushKeyCase197c(r: Random): PushKeyCase197c =
    PushKeyCase197c("key", PUSH_KEY_POOL197c[r.nextInt(PUSH_KEY_POOL197c.size)])

// ---------- tests ----------

class FcmPushKeyFuzzTest {

    @Test
    fun pushKeyDifferential197() {
        val r = Random(197301)
        repeat(300) {
            val c = randomPushKeyCase197c(r)
            assertEquals(pushKeyOracle197c(c), pushKeyProd197c(c))
        }
    }

    @Test
    fun pushKeyBoundaryDifferential197() {
        val r = Random(197302)
        repeat(300) {
            val c = boundaryPushKeyCase197c(r)
            assertEquals(pushKeyOracle197c(c), pushKeyProd197c(c))
        }
    }

    @Test
    fun pushKeyPinned197() {
        // 密钥派生是确定性 HMAC：同输入同输出、不同输入大概率不同、输出 64 位小写 hex。
        val a = pushKeyProd197c("u1")
        assertEquals(64, a.length)
        assertEquals(a, pushKeyOracle197c("u1"))
        assertEquals(a, pushKeyProd197c("u1"))
        assertEquals(pushKeyProd197c(""), pushKeyOracle197c(""))
        assertEquals(pushKeyProd197c("user-123"), pushKeyOracle197c("user-123"))
        assertEquals(pushKeyProd197c("中文用户"), pushKeyOracle197c("中文用户"))
        assertEquals(pushKeyProd197c("x".repeat(1000)), pushKeyOracle197c("x".repeat(1000)))
        assertEquals(pushKeyProd197c("a\uD800b"), pushKeyOracle197c("a\uD800b"))
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            PushKeyCase197c("key", ""),
            PushKeyCase197c("key", "u1"),
            PushKeyCase197c("key", "a/b"),
            PushKeyCase197c("key", "x".repeat(65)),
            PushKeyCase197c("key", "é"),
        )) assertEquals(pushKeyOracle197c(c), pushKeyProd197c(c))
    }
}
