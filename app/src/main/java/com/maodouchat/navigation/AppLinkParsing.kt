package com.maodouchat.navigation

/**
 * 解析外部深链。大小写规则：scheme/host 按 ASCII 小写归一化后匹配白名单
 *（与 Android Uri 行为对齐，浏览器实际发出的均为小写）；path 与参数值保持原样、
 * 走各清洗器校验。
 * 支持形态：
 * - maodouchat://u/{username}
 * - https://chat.mdou.me/u/{username}（容忍 ?embed= 查询）
 * - maodouchat://chat/{chatId}[?messageId=...]
 * - https://chat.mdou.me/c/{chatId}
 * - maodouchat://post/{postId}[?comment=...]
 * - maodouchat://invite/{code} / https://chat.mdou.me/join/{code}
 * - maodouchat:chat-invite:v1:{token}（生产分享/QR 冒号协议，与 QrPayloadParser 同构）
 */
internal fun AppLinkRouter.parseDeepLink(uriString: String): AppLinkParseResult {
    val raw = uriString.trim()
    if (raw.isEmpty()) return AppLinkParseResult.Rejected("empty")
    // 生产邀请载荷无 ://（maodouchat:chat-invite:v1:…），与深链共用同一 GroupInvite 目标。
    if (raw.startsWith(AppLinkRouter.CHAT_INVITE_COLON_PREFIX, ignoreCase = true)) {
        val token = sanitizeChatInviteToken(raw.substring(AppLinkRouter.CHAT_INVITE_COLON_PREFIX.length))
            ?: return AppLinkParseResult.Rejected("bad_invite")
        return AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(token))
    }
    // 仅允许白名单 scheme/host 组合，拒绝 javascript:/file:/content: 等。
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return AppLinkParseResult.Rejected("unsupported")
    val scheme = raw.substring(0, schemeEnd).lowercase()
    val afterScheme = raw.substring(schemeEnd + 3)
    return when {
        scheme == "maodouchat" -> parseMaodouScheme(afterScheme)
        scheme == "https" && afterScheme.substringBefore('/').lowercase() == "chat.mdou.me" &&
            afterScheme.contains('/') ->
            parseHttpsChatMdou(afterScheme.substringAfter('/'))
        else -> AppLinkParseResult.Rejected("unsupported")
    }
}

/**
 * 用户面链接统一入口（聊天气泡 / 媒体中心链接 Tab / 预览卡）。
 * 1) 先走 [parseDeepLink]：应用内白名单深链；
 * 2) 否则仅接受清洗后的 http(s) → [AppLinkDestination.ExternalUrl]；
 * 3) 其它 scheme（javascript:/file:/intent: 等）拒绝。
 *
 * 不把任意 host 并入 [parseDeepLink]，以免破坏 Manifest / 深链白名单语义。
 */
internal fun AppLinkRouter.resolveUserFacingUrl(raw: String): AppLinkParseResult {
    when (val deep = parseDeepLink(raw)) {
        is AppLinkParseResult.Accepted -> return deep
        is AppLinkParseResult.Rejected -> Unit
    }
    val http = sanitizeHttpUrl(raw) ?: return AppLinkParseResult.Rejected("unsupported")
    return AppLinkParseResult.Accepted(AppLinkDestination.ExternalUrl(http))
}
