package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr195c(s: String): String {
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

private fun decStr195c(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt195c(s: String?): String = if (s == null) "NULL" else "S:" + encStr195c(s)

// 两条文件名正则的独立重实现（与 FileStorageService 上的模式逐字一致）。
private fun safeFilenameOracle195c(s: String): Boolean =
    s.matches(Regex("^[A-Za-z0-9_.-]+$"))

private fun imageSuffixOracle195c(s: String): Boolean =
    s.matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))

// avatarUrl 的独立重实现：只认安全文件名字符，baseUrl 取同一份服务配置值。
private fun avatarUrlOracle195c(filename: String): String? {
    if (!safeFilenameOracle195c(filename)) return null
    return "${FileStorageService.baseUrl}/api/files/avatar/$filename"
}

private fun isOwnedAvatarFilenameOracle195c(filename: String, userId: String): Boolean {
    if (userId.isBlank()) return false
    val safeUserId = userId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val suffix = filename.removePrefix("avatar_${safeUserId}_")
    return suffix != filename && imageSuffixOracle195c(suffix)
}

private fun avatarUrlProd195c(filename: String): String? =
    FileStorageService.avatarUrl(filename)

private fun isOwnedAvatarFilenameProd195c(filename: String, userId: String): Boolean =
    FileStorageService.isOwnedAvatarFilename(filename, userId)

// op: url | owned。
private data class AvatarCase195c(
    val op: String,
    val s1: String = "",
    val s2: String = "",
)

private fun avatarOracle195c(c: AvatarCase195c): String = when (c.op) {
    "url" -> encOpt195c(avatarUrlOracle195c(c.s1))
    "owned" -> isOwnedAvatarFilenameOracle195c(c.s1, c.s2).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun avatarProd195c(c: AvatarCase195c): String = when (c.op) {
    "url" -> encOpt195c(avatarUrlProd195c(c.s1))
    "owned" -> isOwnedAvatarFilenameProd195c(c.s1, c.s2).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val AV_FILENAMES195c = listOf(
    "avatar_u1_0123abcd.jpg", "avatar_u1_0123ABCD.jpg", "avatar_u1_0123abcD.jpg",
    "avatar_u1_0123abc.jpg", "avatar_u1_0123abcde.jpg", "avatar_u1_0123abcd.JPG",
    "avatar_u1_0123abcd.png", "avatar_u1_0123abcd.jpg ", " avatar_u1_0123abcd.jpg",
    "avatar__0123abcd.jpg", "avatar_u1_.jpg", "avatar_u1_0123abcd.jpg\n",
    "avatar_u1_0123abcd.jpg\r\n", "avatar_u-1_0123abcd.jpg", "avatar_u.1_0123abcd.jpg",
    "avatar_u1_0123abcd.jpeg", "avatar_u1_0123abcd.jpg/x", "avatar_u1_0123abcd",
    "avatar_", "avatar_u1_", "avatar_u1_0123abcd.jpg\u0000", "avatar_u1_0123abcd.jpg\u007f",
    "", "avatar_u1_g123abcd.jpg", "avatar_u1_0123abcg.jpg", "post_u1_0123abcd.jpg",
    "avatar_\u00e9_0123abcd.jpg", "avatar_u1_0123abcd.jpg ", "x", "a".repeat(100),
    "avatar_u1_" + "a".repeat(8) + ".jpg", "avatar_u1_0123abcd.jpg\u0085",
    "avatar_u1_0123abcd.jpg\u00a0", "AVATAR_u1_0123abcd.jpg", "avatar_u1_0123abcd.Jpg"
)

private val AV_USERIDS195c = listOf(
    "u1", "user-1", "a/b", "a\\b", "a..b", "...", "", " ", "..", "/", "\\",
    "a..b..c", "\u00e9\ud83d\ude00", "x".repeat(40), "avatar_u1", "_", ".",
    "u.1", "u 1", "u\t1", "u\n1", "..u1..", "a/b\\c..d", "u1_", "_u1", "0"
)

private val AV_RANDOM_CHARS195c = listOf(
    "a", "b", "c", "u", "1", "0", "_", "-", ".", "/", "\\", "j", "p", "g",
    "A", "F", "f", "9", " ", "\n", "\t", "\u00e9", "\ud83d\ude00", ":", "|",
    "?", "*", "\"", "<", ">", "%", "#", "@", "!", "~", "=", "+", ";", ",",
    "'", "(", ")", "[", "]", "{", "}", "$", "`", "\u0000", "\u007f"
)

private val AV_HEX195c = "0123456789abcdefABCDEF"

private fun randomAvatarFilename195c(r: Random): String {
    return when (r.nextInt(4)) {
        0 -> {
            val uid = AV_USERIDS195c[r.nextInt(AV_USERIDS195c.size)]
            val hex = (0 until r.nextInt(5, 12)).joinToString("") { AV_HEX195c[r.nextInt(AV_HEX195c.length)].toString() }
            val ext = listOf(".jpg", ".png", ".JPG", "", ".jpeg")[r.nextInt(5)]
            "avatar_${uid}_$hex$ext"
        }
        1 -> AV_FILENAMES195c[r.nextInt(AV_FILENAMES195c.size)]
        2 -> {
            val uid = AV_USERIDS195c[r.nextInt(AV_USERIDS195c.size)]
            "avatar_${uid}_" + (0 until 8).joinToString("") { AV_HEX195c[r.nextInt(16)].toString() } + ".jpg"
        }
        else -> (0 until r.nextInt(30)).joinToString("") { AV_RANDOM_CHARS195c[r.nextInt(AV_RANDOM_CHARS195c.size)] }
    }
}

private fun randomAvatarUserId195c(r: Random): String {
    return if (r.nextInt(3) == 0) AV_USERIDS195c[r.nextInt(AV_USERIDS195c.size)]
    else (0 until r.nextInt(12)).joinToString("") { AV_RANDOM_CHARS195c[r.nextInt(AV_RANDOM_CHARS195c.size)] }
}

private fun randomAvatarCase195c(r: Random): AvatarCase195c {
    return if (r.nextInt(2) == 0) {
        AvatarCase195c("url", randomAvatarFilename195c(r))
    } else {
        AvatarCase195c("owned", randomAvatarFilename195c(r), randomAvatarUserId195c(r))
    }
}

private fun boundaryAvatarCase195c(r: Random): AvatarCase195c {
    return if (r.nextInt(2) == 0) {
        AvatarCase195c("url", AV_FILENAMES195c[r.nextInt(AV_FILENAMES195c.size)])
    } else {
        AvatarCase195c("owned", AV_FILENAMES195c[r.nextInt(AV_FILENAMES195c.size)], AV_USERIDS195c[r.nextInt(AV_USERIDS195c.size)])
    }
}

// ---------- tests ----------

class FileStorageAvatarFuzzTest {

    @Test
    fun avatarDifferential195() {
        val r = Random(195005)
        repeat(300) {
            val c = randomAvatarCase195c(r)
            assertEquals(avatarOracle195c(c), avatarProd195c(c))
        }
    }

    @Test
    fun avatarBoundaryDifferential195() {
        val r = Random(195006)
        repeat(300) {
            val c = boundaryAvatarCase195c(r)
            assertEquals(avatarOracle195c(c), avatarProd195c(c))
        }
    }

    @Test
    fun avatarPinned195() {
        val base = FileStorageService.baseUrl
        // avatarUrl 只认安全文件名字符，不校验后缀是不是 .jpg。
        assertEquals("$base/api/files/avatar/avatar_u1_0123abcd.jpg", avatarUrlProd195c("avatar_u1_0123abcd.jpg"))
        assertEquals("$base/api/files/avatar/avatar_u1_0123abcd.png", avatarUrlProd195c("avatar_u1_0123abcd.png"))
        assertEquals(null, avatarUrlProd195c("a/b.jpg"))
        assertEquals(null, avatarUrlProd195c("a b.jpg"))
        assertEquals(null, avatarUrlProd195c(""))
        assertEquals("$base/api/files/avatar/avatar_u1_0123abcd.jpg", avatarUrlOracle195c("avatar_u1_0123abcd.jpg"))
        // 归属校验：前缀里的 userId 先做路径穿越清洗（/、\、.. 都变 _）。
        assertEquals(true, isOwnedAvatarFilenameProd195c("avatar_u1_0123abcd.jpg", "u1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u2_0123abcd.jpg", "u1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u1_0123abcd.png", "u1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u1_0123abc.jpg", "u1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u1_0123abcde.jpg", "u1"))
        assertEquals(true, isOwnedAvatarFilenameProd195c("avatar_u1_0123ABCD.jpg", "u1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u1_0123abcd.JPG", "u1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u1_0123abcd.jpg", ""))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u1_0123abcd.jpg", " "))
        assertEquals(true, isOwnedAvatarFilenameProd195c("avatar_a_b_0123abcd.jpg", "a/b"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_a/b_0123abcd.jpg", "a/b"))
        assertEquals(true, isOwnedAvatarFilenameProd195c("avatar_u_1_0123abcd.jpg", "u..1"))
        assertEquals(false, isOwnedAvatarFilenameProd195c("avatar_u..1_0123abcd.jpg", "u..1"))
        // 每条固定向量 oracle 与生产一致（baseUrl 相关向量走同一配置值）。
        for (c in listOf(
            AvatarCase195c("url", "avatar_u1_0123abcd.jpg"),
            AvatarCase195c("url", "avatar_u1_0123abcd.png"),
            AvatarCase195c("url", "a/b.jpg"),
            AvatarCase195c("owned", "avatar_u1_0123abcd.jpg", "u1"),
            AvatarCase195c("owned", "avatar_a_b_0123abcd.jpg", "a/b"),
            AvatarCase195c("owned", "avatar_u_1_0123abcd.jpg", "u..1"),
        )) assertEquals(avatarOracle195c(c), avatarProd195c(c))
    }
}
