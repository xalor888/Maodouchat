package com.maodouchat.slim

/**
 * 远程贴纸清单中的路径名约束。
 *
 * 服务端有 canonical 路径校验，但客户端也不能盲信清单；这里保证 packId 和文件名
 * 只用于构造贴纸缓存目录内的相对路径，并明确拒绝 `.` / `..` 这类目录引用。
 */
internal object StickerNamePolicy {

    private val PACK_ID_REGEX = Regex("[^A-Za-z0-9_-]")
    private val FILE_NAME_REGEX = Regex("[^A-Za-z0-9._-]")

    fun sanitizePackId(id: String): String {
        val cleaned = id.trim().replace(PACK_ID_REGEX, "").take(40)
        return if (cleaned == "." || cleaned == "..") "" else cleaned
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(FILE_NAME_REGEX, "").take(80)
        return if (cleaned == "." || cleaned == "..") "" else cleaned
    }
}
