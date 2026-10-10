package com.maodouchat.navigation

/**
 * 轻量 http(s) 清洗（用户主动打开，非 SSRF 抓取策略）。
 * 拒绝控制字符、空白、过长 URL；不拦截内网（由系统浏览器处理）。
 */
internal fun AppLinkRouter.sanitizeHttpUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > AppLinkRouter.MAX_USER_FACING_URL_LENGTH) return null
    if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = trimmed.substring(schemeEnd + 3)
    if (afterScheme.isEmpty() || afterScheme.startsWith('/')) return null
    return trimmed
}

/**
 * 解析通知/Widget/系统入口 extras（key 与 NotificationIntents 对齐）。
 * 所有值均经过与深链相同的清洗器；非法值返回 null（调用方忽略，不导航）。
 */
internal fun AppLinkRouter.parseNotificationExtras(extras: Map<String, String?>): AppLinkDestination? {
    val chatId = extras["open_chat_id"]?.takeIf { it.isNotBlank() }
    val messageId = extras["open_message_id"]?.takeIf { it.isNotBlank() }
    if (chatId != null) {
        val cleanChat = sanitizeChatIdStrict(chatId) ?: return null
        val cleanMsg = messageId?.let { sanitizeMessageIdStrict(it) ?: return null }
        return AppLinkDestination.ChatDetail(chatId = cleanChat, messageId = cleanMsg)
    }
    extras["open_ai_tasks_chat_id"]?.takeIf { it.isNotBlank() }?.let {
        return AppLinkDestination.AiTasksChat(chatId = sanitizeChatIdStrict(it) ?: return null)
    }
    extras["open_post_id"]?.takeIf { it.isNotBlank() }?.let {
        return AppLinkDestination.PostDetail(postId = sanitizePostIdStrict(it) ?: return null)
    }
    extras["open_username"]?.takeIf { it.isNotBlank() }?.let {
        return AppLinkDestination.PublicProfile(username = sanitizeUsername(it) ?: return null)
    }
    return null
}

/**
 * 通知中心持久化行使用的 legacy 字符串（`maodouchat:chat:` 等，非 `://` 深链）。
 * 非法/注入值返回 null；调用方忽略，不导航。
 */
internal fun AppLinkRouter.parseLegacyCenterDeeplink(raw: String): AppLinkDestination? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return when {
        value == "maodouchat:missed_calls" -> AppLinkDestination.MissedCallsTab
        value == "maodouchat:contacts" -> AppLinkDestination.ContactsTab
        value == "maodouchat:group_invites" -> AppLinkDestination.GroupInvitesTab
        value.startsWith("maodouchat:chat:") -> {
            val id = value.removePrefix("maodouchat:chat:").substringBefore(':')
            AppLinkDestination.ChatDetail(chatId = sanitizeChatIdStrict(id) ?: return null)
        }
        value.startsWith("maodouchat:ai_tasks:") -> {
            val id = value.removePrefix("maodouchat:ai_tasks:").substringBefore(':')
            AppLinkDestination.AiTasksChat(chatId = sanitizeChatIdStrict(id) ?: return null)
        }
        value.startsWith("maodouchat:post:") -> {
            val payload = value.removePrefix("maodouchat:post:")
            val postRaw = payload.substringBefore('?').trim()
            val commentRaw = payload.substringAfter("?comment=", "")
                .trim()
                .takeIf { it.isNotBlank() }
            val postId = sanitizePostIdStrict(postRaw) ?: return null
            val commentId = commentRaw?.let { sanitizeMessageIdStrict(it) ?: return null }
            AppLinkDestination.PostDetail(postId = postId, commentId = commentId)
        }
        else -> null
    }
}

// ---- 清洗器（与 MainActivity 现有用户名规则对齐，其余 ID 取交集最严形态） ----

internal fun AppLinkRouter.sanitizeUsername(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_USERNAME_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') }) return null
    return single
}

internal fun AppLinkRouter.sanitizeChatId(raw: String): String? = sanitizeOpaqueId(raw)

internal fun AppLinkRouter.sanitizeMessageId(raw: String): String? = sanitizeOpaqueId(raw)

internal fun AppLinkRouter.sanitizePostId(raw: String): String? = sanitizeOpaqueId(raw)

internal fun AppLinkRouter.sanitizeInviteCode(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_INVITE_CODE_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
    return single
}

/**
 * 生产 chat-invite token：严格 32–80 位 `[A-Za-z0-9_-]`，含 /?# 直接拒绝（不截断）。
 * URL 短码仍走 [sanitizeInviteCode]；QR/冒号载荷必须用本清洗器。
 */
internal fun AppLinkRouter.sanitizeChatInviteToken(raw: String): String? {
    val single = raw.trim()
    if (single.any { it == '/' || it == '?' || it == '#' }) return null
    if (single.length !in AppLinkRouter.MIN_CHAT_INVITE_TOKEN_LENGTH..MAX_INVITE_CODE_LENGTH) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
    return single
}

internal fun AppLinkRouter.sanitizeOpaqueId(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_ID_LENGTH)
    if (single.isBlank()) return null
    // 拒绝路径分隔/空白/控制字符，其余透传（兼容现有 UUID/pairKey 形态）。
    if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
    return single
}

/**
 * 通知/Widget extras 用严格清洗：含路径分隔/查询符的值直接拒绝（而非静默截断），
 * 避免 "c1/evil" 被截成 "c1" 后误导航。合法 ID（UUID/pairKey/服务端雪花）不含这些字符。
 */
internal fun AppLinkRouter.sanitizeChatIdStrict(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeChatId(raw)

internal fun AppLinkRouter.sanitizeMessageIdStrict(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeMessageId(raw)

internal fun AppLinkRouter.sanitizePostIdStrict(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizePostId(raw)

/**
 * 来电唤醒入口（Telecom/FCM）用严格清洗：callId/senderId 均为服务端 opaque ID，
 * 含 /?# 直接拒绝。返回 null 时调用方按空串处理（仍走通用轮询，不定向响铃）。
 */
internal fun AppLinkRouter.sanitizeCallIdStrict(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueId(raw)

internal fun AppLinkRouter.sanitizeUserIdStrict(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueId(raw)
