package com.maodouchat.crypto

/**
 * Recognizes decrypt-failure UI text that older clients may have persisted as message content.
 *
 * Matching is deliberately limited to complete historical placeholder strings. User plaintext
 * may legitimately discuss decryption, sessions, or missing keys and must remain readable.
 */
object DecryptPlaceholderPolicy {
    private val LEGACY_PLACEHOLDERS = setOf(
        "无法解密",
        "解密失败",
        "群密钥缺失",
        "密钥缺失",
        "decrypt failed",
        "session missing",
        "identity changed",
        "[无法解密的消息]",
        "[无法解密的群聊消息]",
        "[加密消息]",
        "[缺少会话密钥，请等待对方重新发送]",
        "[对方安全码已变化，请验证身份]",
        "[群成员安全码已变化，请验证身份]",
        "[缺少群聊 sender key，请等待成员重新发送]",
        "[群聊密钥 epoch 较新，请刷新群信息]",
        "[无法解密的图片]",
        "[无法解密的 gif]",
        "[无法解密的贴纸]",
        "[无法解密的位置]",
        "[无法解密的视频]",
        "[无法解密的语音]",
        "[无法解密的文件]",
        "[unable to decrypt message]",
        "[unable to decrypt group message]",
        "[encrypted message]",
        "[missing session key. waiting for resend]",
        "[safety number changed. verify identity]",
        "[member safety number changed. verify identity]",
        "[missing group sender key. waiting for redistribution]",
        "[newer group key epoch. refresh group info]",
        "[unable to decrypt image]",
        "[unable to decrypt gif]",
        "[unable to decrypt sticker]",
        "[unable to decrypt location]",
        "[unable to decrypt video]",
        "[unable to decrypt voice message]",
        "[unable to decrypt file]",
    )

    fun isPlaceholder(content: String, vararg localizedPlaceholders: String): Boolean {
        val normalized = normalize(content)
        if (normalized.isEmpty()) return false
        return normalized in LEGACY_PLACEHOLDERS ||
            localizedPlaceholders.any { normalize(it) == normalized }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
