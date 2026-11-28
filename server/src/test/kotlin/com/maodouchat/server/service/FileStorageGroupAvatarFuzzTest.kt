package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr196c(s: String): String {
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

private fun decStr196c(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt196c(s: String?): String = if (s == null) "NULL" else "S:" + encStr196c(s)

// 两条文件名正则的独立重实现（与 FileStorageService 上的模式逐字一致）。
private fun safeFilenameOracle196c(s: String): Boolean =
    s.matches(Regex("^[A-Za-z0-9_.-]+$"))

private fun imageSuffixOracle196c(s: String): Boolean =
    s.matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))

// isOwnedGroupAvatarFilename 的独立重实现：chatId 空白即假；文件名须先过安全字符正则；
// chatId 再做路径穿越清洗。
private fun isOwnedGroupAvatarFilenameOracle196c(filename: String, chatId: String): Boolean {
    if (chatId.isBlank() || !safeFilenameOracle196c(filename)) return false
    val safeChatId = chatId.replace("/", "_").replace("\\", "_").replace("..", "_")
    val suffix = filename.removePrefix("group_${safeChatId}_")
    return suffix != filename && imageSuffixOracle196c(suffix)
}

// isGroupAvatarFilename 的独立重实现：安全文件名 + group_ 前缀 + 末段 8 位 hex .jpg。
private fun isGroupAvatarFilenameOracle196c(filename: String): Boolean =
    filename.matches(Regex("^[A-Za-z0-9_.-]+$")) && filename.startsWith("group_") &&
        filename.substringAfterLast('_', "").matches(Regex("^[A-Fa-f0-9]{8}\\.jpg$"))

// groupAvatarFilename 的独立重实现：只认 <baseUrl>/api/chats/<chatId>/avatar/file/<文件名> 四段。
private fun groupAvatarFilenameOracle196c(url: String?, expectedChatId: String?): String? {
    val prefix = "${FileStorageService.baseUrl}/api/chats/"
    val relative = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
    val parts = relative.split('/')
    if (parts.size != 4 || parts[1] != "avatar" || parts[2] != "file") return null
    val chatId = parts[0]
    if (expectedChatId != null && chatId != expectedChatId) return null
    val filename = parts[3]
    return filename.takeIf { isOwnedGroupAvatarFilenameOracle196c(it, chatId) }
}

private fun groupAvatarFilenameProd196c(url: String?, expectedChatId: String?): String? =
    FileStorageService.groupAvatarFilename(url, expectedChatId)

private fun isOwnedGroupAvatarFilenameProd196c(filename: String, chatId: String): Boolean =
    FileStorageService.isOwnedGroupAvatarFilename(filename, chatId)

private fun isGroupAvatarFilenameProd196c(filename: String): Boolean =
    FileStorageService.isGroupAvatarFilename(filename)

// op: file | owned | isfile。
// file 用 s1=url、n1=url 为 null、s2=期望 chatId、n2=期望 chatId 为 null；
// owned 用 s1=文件名、s2=chatId；isfile 用 s1=文件名。
private data class GroupAvatarCase196c(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun groupAvatarOracle196c(c: GroupAvatarCase196c): String = when (c.op) {
    "file" -> encOpt196c(
        groupAvatarFilenameOracle196c(
            if (c.n1 == 1) null else c.s1, if (c.n2 == 1) null else c.s2
        )
    )
    "owned" -> isOwnedGroupAvatarFilenameOracle196c(c.s1, c.s2).toString()
    "isfile" -> isGroupAvatarFilenameOracle196c(c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun groupAvatarProd196c(c: GroupAvatarCase196c): String = when (c.op) {
    "file" -> encOpt196c(
        groupAvatarFilenameProd196c(
            if (c.n1 == 1) null else c.s1, if (c.n2 == 1) null else c.s2
        )
    )
    "owned" -> isOwnedGroupAvatarFilenameProd196c(c.s1, c.s2).toString()
    "isfile" -> isGroupAvatarFilenameProd196c(c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val GAV_CHATIDS196c = listOf(
    "c1", "chat-1", "a/b", "a\\b", "a..b", "...", "", " ", "..", "/", "\\",
    "a..b..c", "\u00e9\ud83d\ude00", "x".repeat(40), "group_c1", "_", ".",
    "c.1", "c 1", "c\t1", "c\n1", "..c1..", "a/b\\c..d", "c1_", "_c1", "0"
)

private val GAV_FILENAMES196c = listOf(
    "group_c1_0123abcd.jpg", "group_c1_0123ABCD.jpg", "group_c1_0123abcD.jpg",
    "group_c1_0123abc.jpg", "group_c1_0123abcde.jpg", "group_c1_0123abcd.JPG",
    "group_c1_0123abcd.png", "group_c1_0123abcd.jpg ", " group_c1_0123abcd.jpg",
    "group__0123abcd.jpg", "group_c1_.jpg", "group_c1_0123abcd.jpg\n",
    "group_c1_0123abcd.jpg\r\n", "group_c-1_0123abcd.jpg", "group_c.1_0123abcd.jpg",
    "group_c1_0123abcd.jpeg", "group_c1_0123abcd.jpg/x", "group_c1_0123abcd",
    "group_", "group_c1_", "group_c1_0123abcd.jpg\u0000", "group_c1_0123abcd.jpg\u007f",
    "", "group_c1_g123abcd.jpg", "group_c1_0123abcg.jpg", "post_c1_0123abcd.jpg",
    "group_\u00e9_0123abcd.jpg", "avatar_c1_0123abcd.jpg", "x", "a".repeat(100),
    "group_c1_" + "a".repeat(8) + ".jpg", "group_c1_0123abcd.jpg\u0085",
    "group_c1_0123abcd.jpg\u00a0", "GROUP_c1_0123abcd.jpg", "group_c1_0123abcd.Jpg",
    "group_c1_0123abcd.jpg.jpg", "group_c1_0123abcd..jpg", "group__c1_0123abcd.jpg"
)

private val GAV_RANDOM_CHARS196c = listOf(
    "a", "b", "c", "g", "r", "o", "u", "p", "1", "0", "_", "-", ".", "/", "\\",
    "j", "f", "A", "F", "9", " ", "\n", "\t", "\u00e9", "\ud83d\ude00", ":",
    "|", "?", "*", "\"", "<", ">", "%", "#", "@", "!", "~", "=", "+", ";",
    ",", "'", "(", ")", "[", "]", "{", "}", "$", "`", "\u0000", "\u007f"
)

private val GAV_HEX196c = "0123456789abcdefABCDEF"

private fun randomGroupAvatarFilename196c(r: Random): String {
    return when (r.nextInt(4)) {
        0 -> {
            val cid = GAV_CHATIDS196c[r.nextInt(GAV_CHATIDS196c.size)]
            val hex = (0 until r.nextInt(5, 12)).joinToString("") { GAV_HEX196c[r.nextInt(GAV_HEX196c.length)].toString() }
            val ext = listOf(".jpg", ".png", ".JPG", "", ".jpeg")[r.nextInt(5)]
            "group_${cid}_$hex$ext"
        }
        1 -> GAV_FILENAMES196c[r.nextInt(GAV_FILENAMES196c.size)]
        2 -> {
            val cid = GAV_CHATIDS196c[r.nextInt(GAV_CHATIDS196c.size)]
            "group_${cid}_" + (0 until 8).joinToString("") { GAV_HEX196c[r.nextInt(16)].toString() } + ".jpg"
        }
        else -> (0 until r.nextInt(30)).joinToString("") { GAV_RANDOM_CHARS196c[r.nextInt(GAV_RANDOM_CHARS196c.size)] }
    }
}

private fun randomGroupAvatarChatId196c(r: Random): String {
    return if (r.nextInt(3) == 0) GAV_CHATIDS196c[r.nextInt(GAV_CHATIDS196c.size)]
    else (0 until r.nextInt(12)).joinToString("") { GAV_RANDOM_CHARS196c[r.nextInt(GAV_RANDOM_CHARS196c.size)] }
}

private fun randomGroupAvatarUrl196c(r: Random): String {
    val base = FileStorageService.baseUrl
    return when (r.nextInt(5)) {
        0 -> "$base/api/chats/${randomGroupAvatarChatId196c(r)}/avatar/file/${randomGroupAvatarFilename196c(r)}"
        1 -> "$base/api/chats/${randomGroupAvatarChatId196c(r)}/avatar/file/${randomGroupAvatarFilename196c(r)}/extra"
        2 -> "$base/api/chats/${randomGroupAvatarChatId196c(r)}/avatar/${randomGroupAvatarFilename196c(r)}"
        3 -> "$base/api/files/avatar/${randomGroupAvatarFilename196c(r)}"
        else -> (0 until r.nextInt(40)).joinToString("") { GAV_RANDOM_CHARS196c[r.nextInt(GAV_RANDOM_CHARS196c.size)] }
    }
}

private fun randomGroupAvatarCase196c(r: Random): GroupAvatarCase196c = when (r.nextInt(3)) {
    0 -> {
        val urlNull = r.nextInt(8) == 0
        val expNull = r.nextInt(2) == 0
        GroupAvatarCase196c(
            "file",
            if (urlNull) "" else randomGroupAvatarUrl196c(r),
            if (expNull) "" else randomGroupAvatarChatId196c(r),
            if (urlNull) 1 else 0, if (expNull) 1 else 0
        )
    }
    1 -> GroupAvatarCase196c(
        "owned", randomGroupAvatarFilename196c(r), randomGroupAvatarChatId196c(r)
    )
    else -> GroupAvatarCase196c("isfile", randomGroupAvatarFilename196c(r))
}

private fun boundaryGroupAvatarCase196c(r: Random): GroupAvatarCase196c = when (r.nextInt(3)) {
    0 -> {
        val cid = GAV_CHATIDS196c[r.nextInt(GAV_CHATIDS196c.size)]
        val fn = GAV_FILENAMES196c[r.nextInt(GAV_FILENAMES196c.size)]
        val base = FileStorageService.baseUrl
        val url = when (r.nextInt(4)) {
            0 -> "$base/api/chats/$cid/avatar/file/$fn"
            1 -> "$base/api/chats/$cid/avatar/file/$fn/"
            2 -> "$base/api/chats/$cid/avatar/file/"
            else -> "$base/api/chats//avatar/file/$fn"
        }
        GroupAvatarCase196c(
            "file", url,
            if (r.nextInt(2) == 0) "" else cid,
            0, if (r.nextInt(2) == 0) 1 else 0
        )
    }
    1 -> GroupAvatarCase196c(
        "owned", GAV_FILENAMES196c[r.nextInt(GAV_FILENAMES196c.size)],
        GAV_CHATIDS196c[r.nextInt(GAV_CHATIDS196c.size)]
    )
    else -> GroupAvatarCase196c("isfile", GAV_FILENAMES196c[r.nextInt(GAV_FILENAMES196c.size)])
}

// ---------- tests ----------

class FileStorageGroupAvatarFuzzTest {

    @Test
    fun groupAvatarDifferential196() {
        val r = Random(196105)
        repeat(300) {
            val c = randomGroupAvatarCase196c(r)
            assertEquals(groupAvatarOracle196c(c), groupAvatarProd196c(c))
        }
    }

    @Test
    fun groupAvatarBoundaryDifferential196() {
        val r = Random(196106)
        repeat(300) {
            val c = boundaryGroupAvatarCase196c(r)
            assertEquals(groupAvatarOracle196c(c), groupAvatarProd196c(c))
        }
    }

    @Test
    fun groupAvatarPinned196() {
        val base = FileStorageService.baseUrl
        // groupAvatarFilename：只认四段路径，文件名还要过归属校验。
        assertEquals(
            "group_c1_0123abcd.jpg",
            groupAvatarFilenameProd196c("$base/api/chats/c1/avatar/file/group_c1_0123abcd.jpg", null)
        )
        assertEquals(
            "group_c1_0123abcd.jpg",
            groupAvatarFilenameProd196c("$base/api/chats/c1/avatar/file/group_c1_0123abcd.jpg", "c1")
        )
        assertEquals(
            null,
            groupAvatarFilenameProd196c("$base/api/chats/c1/avatar/file/group_c1_0123abcd.jpg", "c2")
        )
        assertEquals(
            null,
            groupAvatarFilenameProd196c("$base/api/chats/c2/avatar/file/group_c1_0123abcd.jpg", null)
        )
        assertEquals(null, groupAvatarFilenameProd196c("$base/api/files/avatar/group_c1_0123abcd.jpg", null))
        assertEquals(null, groupAvatarFilenameProd196c("$base/api/chats/c1/avatar/group_c1_0123abcd.jpg", null))
        assertEquals(null, groupAvatarFilenameProd196c("$base/api/chats/c1/avatar/file/group_c1_0123abcd.jpg/extra", null))
        assertEquals(null, groupAvatarFilenameProd196c("$base/api/chats/c1/avatar/file/group_c1_0123abcd.png", null))
        assertEquals(null, groupAvatarFilenameProd196c(null, null))
        assertEquals(null, groupAvatarFilenameProd196c("", null))
        // chatId 含 / 时 URL 路径变 5 段，四段校验直接拒掉（归属校验里的清洗救不回来）。
        assertEquals(
            null,
            groupAvatarFilenameProd196c("$base/api/chats/a/b/avatar/file/group_a_b_0123abcd.jpg", "a/b")
        )
        // isOwnedGroupAvatarFilename：前缀里的 chatId 先做路径穿越清洗。
        assertEquals(true, isOwnedGroupAvatarFilenameProd196c("group_c1_0123abcd.jpg", "c1"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c2_0123abcd.jpg", "c1"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c1_0123abcd.png", "c1"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c1_0123abc.jpg", "c1"))
        assertEquals(true, isOwnedGroupAvatarFilenameProd196c("group_c1_0123ABCD.jpg", "c1"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c1_0123abcd.JPG", "c1"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c1_0123abcd.jpg", ""))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c1_0123abcd.jpg", " "))
        assertEquals(true, isOwnedGroupAvatarFilenameProd196c("group_a_b_0123abcd.jpg", "a/b"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_a/b_0123abcd.jpg", "a/b"))
        assertEquals(true, isOwnedGroupAvatarFilenameProd196c("group_c_1_0123abcd.jpg", "c..1"))
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c..1_0123abcd.jpg", "c..1"))
        // 文件名含换行符：安全文件名正则先判假，后缀再像也不认。
        assertEquals(false, isOwnedGroupAvatarFilenameProd196c("group_c\n1_0123abcd.jpg", "c\n1"))
        assertEquals(
            null,
            groupAvatarFilenameProd196c("$base/api/chats/c\n1/avatar/file/group_c\n1_0123abcd.jpg", null)
        )
        // isGroupAvatarFilename：安全文件名 + group_ 前缀 + 末段 8 位 hex .jpg。
        assertEquals(true, isGroupAvatarFilenameProd196c("group_c1_0123abcd.jpg"))
        assertEquals(true, isGroupAvatarFilenameProd196c("group_x_deadBEEF.jpg"))
        assertEquals(false, isGroupAvatarFilenameProd196c("avatar_c1_0123abcd.jpg"))
        assertEquals(false, isGroupAvatarFilenameProd196c("group_c1_0123abcd.png"))
        assertEquals(false, isGroupAvatarFilenameProd196c("group_c1_0123abc.jpg"))
        assertEquals(false, isGroupAvatarFilenameProd196c("group_c1_0123abcd.jpg/x"))
        assertEquals(false, isGroupAvatarFilenameProd196c("group_c1_0123abcd.JPG"))
        assertEquals(false, isGroupAvatarFilenameProd196c(""))
        assertEquals(false, isGroupAvatarFilenameProd196c("group_"))
        // 每条固定向量 oracle 与生产一致（baseUrl 相关向量走同一配置值）。
        for (c in listOf(
            GroupAvatarCase196c("file", "$base/api/chats/c1/avatar/file/group_c1_0123abcd.jpg", "", 0, 1),
            GroupAvatarCase196c("file", "$base/api/chats/c1/avatar/file/group_c1_0123abcd.jpg", "c1", 0, 0),
            GroupAvatarCase196c("file", "", "", 1, 1),
            GroupAvatarCase196c("owned", "group_c1_0123abcd.jpg", "c1"),
            GroupAvatarCase196c("owned", "group_a_b_0123abcd.jpg", "a/b"),
            GroupAvatarCase196c("isfile", "group_c1_0123abcd.jpg"),
        )) assertEquals(groupAvatarOracle196c(c), groupAvatarProd196c(c))
    }
}
