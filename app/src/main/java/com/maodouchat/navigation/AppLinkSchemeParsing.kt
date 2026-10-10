package com.maodouchat.navigation

// ---- 内部解析 ----

internal fun AppLinkRouter.parseMaodouScheme(afterScheme: String): AppLinkParseResult {
    val pathAndQuery = afterScheme
    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', "")
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return AppLinkParseResult.Rejected("empty_path")
    return when (segments[0]) {
        "u" -> {
            val username = sanitizeUsername(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_username")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_username")
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile(username))
        }
        "chat" -> {
            val chatId = sanitizeChatId(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_chat")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_chat")
            val messageId = parseQueryParam(query, "messageId")?.let {
                sanitizeMessageId(it) ?: return AppLinkParseResult.Rejected("bad_message")
            }
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail(chatId, messageId))
        }
        "post" -> {
            val postId = sanitizePostId(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_post")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_post")
            val comment = parseQueryParam(query, "comment")?.let {
                sanitizeMessageId(it) ?: return AppLinkParseResult.Rejected("bad_comment")
            }
            AppLinkParseResult.Accepted(AppLinkDestination.PostDetail(postId, comment))
        }
        "invite" -> {
            val code = sanitizeInviteCode(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_invite")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_invite")
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(code))
        }
        else -> AppLinkParseResult.Rejected("unsupported")
    }
}

internal fun AppLinkRouter.parseHttpsChatMdou(pathAndQuery: String): AppLinkParseResult {
    val path = pathAndQuery.substringBefore('?')
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return AppLinkParseResult.Rejected("empty_path")
    return when (segments[0]) {
        "u" -> {
            val username = sanitizeUsername(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_username")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_username")
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile(username))
        }
        "c" -> {
            val chatId = sanitizeChatId(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_chat")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_chat")
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail(chatId))
        }
        "join" -> {
            val code = sanitizeInviteCode(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_invite")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_invite")
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(code))
        }
        else -> AppLinkParseResult.Rejected("unsupported")
    }
}

internal fun AppLinkRouter.parseQueryParam(query: String, key: String): String? {
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        if (pair.substring(0, eq) == key) {
            return decodeComponent(pair.substring(eq + 1)).takeIf { it.isNotEmpty() }
        }
    }
    return null
}
