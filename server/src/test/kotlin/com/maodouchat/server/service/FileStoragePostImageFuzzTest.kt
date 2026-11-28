package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr197a(s: String): String {
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

private fun decStr197a(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt197a(s: String?): String = if (s == null) "NULL" else "S:" + encStr197a(s)

// isOwnedPostImageFilename 的独立重实现：userId 为空判假；userId 清洗 / \ .. 变 _；
// 文件名去掉 post_<safe>_ 前缀后剩下的须恰为 8 位 hex + .jpg。注意生产这里不校验安全文件名正则。
private fun ownedOracle197a(filename: String, userId: String): Boolean {
    if (userId.isBlank()) return false
    val safe = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val suffix = filename.removePrefix("post_${safe}_")
    return suffix != filename && suffix.matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))
}

// isPostImageFilename 的独立重实现：安全文件名 + post_ 前缀 + 末段 8 位 hex .jpg。
private fun isFileOracle197a(filename: String): Boolean =
    filename.matches(Regex("^[A-Za-z0-9_.-]+$")) && filename.startsWith("post_") &&
        filename.substringAfterLast('_', "").matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))

private fun ownedProd197a(filename: String, userId: String): Boolean =
    FileStorageService.isOwnedPostImageFilename(filename, userId)

private fun isFileProd197a(filename: String): Boolean =
    FileStorageService.isPostImageFilename(filename)

// op: owned | isfile。owned 用 s1=文件名、s2=userId；isfile 只用 s1。
private data class PostCase197a(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun postOracle197a(c: PostCase197a): String = when (c.op) {
    "owned" -> ownedOracle197a(c.s1, c.s2).toString()
    "isfile" -> isFileOracle197a(c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun postProd197a(c: PostCase197a): String = when (c.op) {
    "owned" -> ownedProd197a(c.s1, c.s2).toString()
    "isfile" -> isFileProd197a(c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val POST_USERIDS197a = listOf(
    "u1", "user-1", "a/b", "a\\b", "a..b", "...", "", " ", "..", "/", "\\",
    "a..b..c", "é\ud83d\ude00", "x".repeat(40), "post_u1", "_", ".", "u.1",
    "u 1", "u\t1", "u\n1", "..u1..", "a/b\\c..d", "u1_", "_u1", "0"
)

private val POST_FILENAMES197a = listOf(
    "post_u1_0123abcd.jpg", "post_u1_0123ABCD.jpg", "post_u1_0123abcD.jpg",
    "post_u1_0123abc.jpg", "post_u1_0123abcde.jpg", "post_u1_0123abcd.JPG",
    "post_u1_0123abcd.png", "post_u1_0123abcd.jpg ", " post_u1_0123abcd.jpg",
    "post__0123abcd.jpg", "post_u1_.jpg", "post_u1_0123abcd.jpg\n",
    "post_u1_0123abcd.jpg\r\n", "post_u-1_0123abcd.jpg", "post_u.1_0123abcd.jpg",
    "post_u1_0123abcd.jpeg", "post_u1_0123abcd.jpg/x", "post_u1_0123abcd",
    "post_", "post_u1_", "post_u1_0123abcd.jpg\u0000", "post_u1_0123abcd.jpg\u007f",
    "", "post_u1_g123abcd.jpg", "post_u1_0123abcg.jpg", "group_u1_0123abcd.jpg",
    "post_\u00e9_0123abcd.jpg", "avatar_u1_0123abcd.jpg", "x", "a".repeat(100),
    "post_u1_" + "a".repeat(8) + ".jpg", "post_u1_0123abcd.jpg\u0085",
    "post_u1_0123abcd.jpg\u00a0", "POST_u1_0123abcd.jpg", "post_u1_0123abcd.Jpg",
    "post_u1_0123abcd.jpg.jpg", "post_u1_0123abcd..jpg", "post__u1_0123abcd.jpg"
)

private const val POST_HEX197a = "0123456789abcdefABCDEF"

private const val POST_ALPHABET197a =
    "abcuopst0123456789abcdefABCDEFg_-./\\jpfJPG \n\t\u00e9\ud83d\ude00:\u0000\u007f"

private fun randomPostString197a(r: Random): String {
    if (r.nextInt(4) == 0) return POST_FILENAMES197a[r.nextInt(POST_FILENAMES197a.size)]
    val n = r.nextInt(0, 30)
    return buildString { repeat(n) { append(POST_ALPHABET197a[r.nextInt(POST_ALPHABET197a.length)]) } }
}

private fun randomPostUserId197a(r: Random): String {
    if (r.nextInt(3) == 0) return POST_USERIDS197a[r.nextInt(POST_USERIDS197a.size)]
    val n = r.nextInt(0, 12)
    return buildString { repeat(n) { append(POST_ALPHABET197a[r.nextInt(POST_ALPHABET197a.length)]) } }
}

private fun randomPostFilename197a(r: Random): String = when (r.nextInt(4)) {
    0 -> {
        val uid = randomPostUserId197a(r)
        val hx = buildString { repeat(r.nextInt(5, 12)) { append(POST_HEX197a[r.nextInt(POST_HEX197a.length)]) } }
        val ext = listOf(".jpg", ".png", ".JPG", "", ".jpeg")[r.nextInt(5)]
        "post_${uid}_$hx$ext"
    }
    1 -> POST_FILENAMES197a[r.nextInt(POST_FILENAMES197a.size)]
    2 -> {
        val uid = randomPostUserId197a(r)
        val hx = buildString { repeat(8) { append(POST_HEX197a[r.nextInt(16)]) } }
        "post_${uid}_$hx.jpg"
    }
    else -> randomPostString197a(r)
}

private fun randomPostCase197a(r: Random): PostCase197a = when (r.nextInt(2)) {
    0 -> PostCase197a("owned", randomPostFilename197a(r), randomPostUserId197a(r))
    else -> PostCase197a("isfile", randomPostFilename197a(r))
}

private fun boundaryPostCase197a(r: Random): PostCase197a = when (r.nextInt(2)) {
    0 -> PostCase197a(
        "owned",
        POST_FILENAMES197a[r.nextInt(POST_FILENAMES197a.size)],
        POST_USERIDS197a[r.nextInt(POST_USERIDS197a.size)]
    )
    else -> PostCase197a("isfile", POST_FILENAMES197a[r.nextInt(POST_FILENAMES197a.size)])
}

// ---------- tests ----------

class FileStoragePostImageFuzzTest {

    @Test
    fun postImageDifferential197() {
        val r = Random(197101)
        repeat(300) {
            val c = randomPostCase197a(r)
            assertEquals(postOracle197a(c), postProd197a(c))
        }
    }

    @Test
    fun postImageBoundaryDifferential197() {
        val r = Random(197102)
        repeat(300) {
            val c = boundaryPostCase197a(r)
            assertEquals(postOracle197a(c), postProd197a(c))
        }
    }

    @Test
    fun postImagePinned197() {
        // isOwnedPostImageFilename：userId 清洗后前缀匹配、后缀 8 位 hex .jpg。
        assertEquals(true, ownedProd197a("post_u1_0123abcd.jpg", "u1"))
        assertEquals(true, ownedProd197a("post_u1_0123ABCD.jpg", "u1"))
        assertEquals(false, ownedProd197a("post_u2_0123abcd.jpg", "u1"))
        assertEquals(false, ownedProd197a("post_u1_0123abcd.png", "u1"))
        assertEquals(false, ownedProd197a("post_u1_0123abc.jpg", "u1"))
        assertEquals(false, ownedProd197a("post_u1_0123abcd.JPG", "u1"))
        assertEquals(false, ownedProd197a("post_u1_0123abcd.jpg", ""))
        assertEquals(false, ownedProd197a("post_u1_0123abcd.jpg", " "))
        assertEquals(true, ownedProd197a("post_a_b_0123abcd.jpg", "a/b"))
        assertEquals(false, ownedProd197a("post_a/b_0123abcd.jpg", "a/b"))
        assertEquals(true, ownedProd197a("post_a_b_0123abcd.jpg", "a..b"))
        assertEquals(false, ownedProd197a("post_u1_0123abcd.jpg\n", "u1"))
        // isPostImageFilename：安全文件名 + post_ 前缀 + 末段 8 位 hex .jpg。
        assertEquals(true, isFileProd197a("post_u1_0123abcd.jpg"))
        assertEquals(true, isFileProd197a("post_x_deadBEEF.jpg"))
        assertEquals(false, isFileProd197a("avatar_u1_0123abcd.jpg"))
        assertEquals(false, isFileProd197a("post_u1_0123abcd.png"))
        assertEquals(false, isFileProd197a("post_u1_0123abc.jpg"))
        assertEquals(false, isFileProd197a("post_u1_0123abcd.jpg/x"))
        assertEquals(false, isFileProd197a("post_u1_0123abcd.JPG"))
        assertEquals(false, isFileProd197a(""))
        assertEquals(false, isFileProd197a("post_"))
        assertEquals(false, isFileProd197a("post_u1_0123abcd.jpg "))
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            PostCase197a("owned", "post_u1_0123abcd.jpg", "u1"),
            PostCase197a("owned", "post_a_b_0123abcd.jpg", "a/b"),
            PostCase197a("owned", "post_u1_0123abcd.jpg", ""),
            PostCase197a("isfile", "post_u1_0123abcd.jpg"),
            PostCase197a("isfile", "post_u1_0123abcd.png"),
            PostCase197a("isfile", ""),
        )) assertEquals(postOracle197a(c), postProd197a(c))
    }
}
