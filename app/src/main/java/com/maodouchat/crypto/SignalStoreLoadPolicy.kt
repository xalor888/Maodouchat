package com.maodouchat.crypto

/**
 * Room `signal_keys` 行是否可加载 / 可写。腐败、空数据必须丢弃，不能让
 * Base64 / libsignal 构造器或 DAO NPE 打崩进程。
 */
object SignalStoreLoadPolicy {
    fun shouldLoadRow(keyType: String?, keyData: String?): Boolean {
        if (keyType.isNullOrBlank() || keyData.isNullOrBlank()) return false
        val trimmedType = keyType.trim()
        if (trimmedType.isEmpty()) return false
        // 空/纯空白 Base64 解码后无法构成合法密钥记录
        if (keyData.isBlank()) return false
        return true
    }

    fun isPersistable(keyType: String?, data: ByteArray?): Boolean {
        if (keyType.isNullOrBlank() || data == null) return false
        return data.isNotEmpty()
    }

    fun logicalKeyOrNull(scopedKeyType: String, prefix: String): String? {
        if (scopedKeyType.isBlank() || prefix.isBlank()) return null
        if (!scopedKeyType.startsWith(prefix)) return scopedKeyType
        val logical = scopedKeyType.removePrefix(prefix)
        return logical.takeIf { it.isNotBlank() }
    }
}
