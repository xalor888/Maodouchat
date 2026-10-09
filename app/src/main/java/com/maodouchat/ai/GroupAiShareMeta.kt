package com.maodouchat.ai

// 分享身份 meta：分享出去的永远是当前用户自己的消息，只打 aiAssisted 标记，不伪造系统身份。
internal object GroupAiShareMeta {
    fun shareAsCurrentUserMeta(mode: String?): Map<String, Any?> = mapOf(
        "aiAssisted" to true,
        "aiAssistantMode" to shareAssistantMode(mode),
        "systemIdentity" to false
    )

    fun shareAiAssistedFlag(): Boolean = true

    fun shareAssistantMode(mode: String?): String? =
        mode?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
}
