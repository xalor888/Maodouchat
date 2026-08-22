package com.maodouchat.security

/**
 * 截屏/录屏防护策略（纯函数）。
 * - App 锁界面始终 FLAG_SECURE
 * - 全局开关：聊天相关界面启用
 * - 密聊：即使全局关闭，只要当前表面属于密聊会话也强制启用
 * - 水印取证页：含可能敏感的泄露截图，始终按聊天表面处理（配合全局开关）
 */
object ScreenSecurePolicy {
    fun shouldSecureWindow(
        appLockShowing: Boolean,
        globalEnabled: Boolean,
        onChatSurface: Boolean,
        secretChatSurfaceActive: Boolean = false,
        chatLockSurfaceActive: Boolean = false
    ): Boolean {
        if (appLockShowing) return true
        if (secretChatSurfaceActive) return true
        // 会话 PIN 锁（ChatLockGate）表面：PIN 属敏感信息，即便全局开关关闭也强制保护。
        if (chatLockSurfaceActive) return true
        return globalEnabled && onChatSurface
    }

    /** 会话详情 / 群详情 / 媒体中心 / 星标 / AI 任务 / 水印取证 / 来电 / 群玩法等含消息内容的界面 */
    fun isChatSurfaceRoute(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        val path = route.substringBefore('?')
        val head = path.substringBefore('/')
        return head in CHAT_SURFACE_HEADS
    }

    /**
     * Extract chatId from chat-surface routes.
     * Path: chat_detail/{id}, chat_detail_two_pane/{id}, group_poll/{id}, …
     * Query: starred_messages?chatId={id}
     */
    fun extractChatIdFromRoute(route: String?): String? {
        if (route.isNullOrBlank()) return null
        val path = route.substringBefore('?')
        val query = route.substringAfter('?', missingDelimiterValue = "")
        val segments = path.split('/').filter { it.isNotBlank() }
        val prefix = segments.getOrNull(0)
        val pathChatId = if (prefix != null && prefix in PATH_CHAT_ID_PREFIXES && segments.size >= 2) {
            decodeSegment(segments[1])
        } else {
            null
        }
        if (!pathChatId.isNullOrBlank()) return pathChatId
        return queryParam(query, "chatId")?.let(::decodeSegment)
    }

    private fun queryParam(query: String, name: String): String? {
        if (query.isBlank()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            value.takeIf { key == name && it.isNotBlank() }
        }
    }

    private fun decodeSegment(raw: String): String? =
        runCatching {
            java.net.URLDecoder.decode(
                raw.replace("+", "%2B"),
                java.nio.charset.StandardCharsets.UTF_8.name()
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private val CHAT_SURFACE_HEADS = setOf(
        "chat_detail",
        "chat_detail_two_pane",
        "chat_detail_list_pane",
        "group_detail",
        "starred_messages",
        "ai_tasks",
        "media_center",
        "watermark_forensic",
        "call",
        "incoming_call",
        "group_poll",
        "group_checkin",
        "group_chain",
        "group_pk"
    )

    private val PATH_CHAT_ID_PREFIXES = setOf(
        "chat_detail",
        "chat_detail_two_pane",
        "group_detail",
        "starred_messages",
        "ai_tasks",
        "media_center",
        "group_poll",
        "group_checkin",
        "group_chain",
        "group_pk"
    )
}
