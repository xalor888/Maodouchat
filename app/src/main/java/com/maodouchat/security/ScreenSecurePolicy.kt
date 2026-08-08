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

    /** 会话详情 / 群详情 / 媒体中心 / 星标 / AI 任务 / 水印取证等含消息内容的界面 */
    fun isChatSurfaceRoute(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        val path = route.substringBefore('?')
        return path.startsWith("chat_detail") ||
            path.startsWith("group_detail") ||
            path.startsWith("starred_messages") ||
            path.startsWith("ai_tasks") ||
            path.startsWith("media_center") ||
            path.startsWith("watermark_forensic") ||
            path.startsWith("call")
    }

    /**
     * Extract chatId from chat-surface routes.
     * Patterns: chat_detail/{id}, media_center/{id}, ai_tasks/{id}, …
     */
    fun extractChatIdFromRoute(route: String?): String? {
        if (route.isNullOrBlank()) return null
        val path = route.substringBefore('?')
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val prefix = segments[0]
        if (
            prefix != "chat_detail" &&
            prefix != "group_detail" &&
            prefix != "starred_messages" &&
            prefix != "ai_tasks" &&
            prefix != "media_center"
        ) {
            return null
        }
        return runCatching {
            android.net.Uri.decode(segments[1])
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
