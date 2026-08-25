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
        // 全局防截屏：整窗 FLAG_SECURE。只绑聊天页时，设置里打开开关当时看不到效果，
        // 且从聊天返回列表的瞬间窗口旗标会被清掉。
        if (globalEnabled) return true
        return false
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
        if (!pathChatId.isNullOrBlank()) return takeRealChatId(pathChatId)
        return takeRealChatId(queryParam(query, "chatId")?.let(::decodeSegment))
    }

    /**
     * Nav Compose `destination.route` is the pattern (`chat_detail/{chatId}`), not the
     * filled URL. Treating `{chatId}` as a real id makes `isSecret("{chatId}")` fail-open
     * and drop FLAG_SECURE after the optimistic window.
     */
    fun isNavPlaceholder(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return chatId.startsWith("{") && chatId.endsWith("}")
    }

    fun takeRealChatId(chatId: String?): String? =
        chatId?.takeIf { it.isNotBlank() && !isNavPlaceholder(it) }

    /**
     * Prefer filled Nav arguments over destination.route pattern.
     * Two-pane parent `chat_detail_list_pane` has no chatId; nested ChatDetail notify fills that hole.
     */
    fun resolveChatId(
        argumentChatId: String?,
        filledRoute: String?,
        routePattern: String?
    ): String? {
        val fromArgs = takeRealChatId(argumentChatId)
        if (fromArgs != null) return fromArgs
        return extractChatIdFromRoute(filledRoute) ?: extractChatIdFromRoute(routePattern)
    }

    /**
     * Substitute `{arg}` tokens in a Nav pattern with filled argument values.
     */
    fun fillRoutePattern(pattern: String?, arguments: Map<String, String?>): String? {
        if (pattern.isNullOrBlank()) return pattern
        return pattern.replace(Regex("\\{([^}]+)\\}")) { match ->
            val key = match.groupValues[1]
            val value = arguments[key]
            if (value.isNullOrBlank()) match.value else value
        }
    }

    /**
     * Optimistic FLAG_SECURE only on surfaces that can immediately show a specific chat's
     * messages. List-pane / incoming-call / forensic have no filled chatId and must not
     * force secret protection (normal chats stay screenshotable unless the global switch is on).
     */
    fun isOptimisticSecretSurface(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        val head = route.substringBefore('?').substringBefore('/')
        return head in OPTIMISTIC_SECRET_HEADS
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

    private val OPTIMISTIC_SECRET_HEADS = setOf(
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
