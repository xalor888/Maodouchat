package com.maodouchat.ui.navigation

/**
 * P08 首切片：应用级统一深链/系统入口路由器（纯 Kotlin，无 Android 依赖）。
 *
 * 现状问题（屎山点）：
 * - 深链解析散落在 [MainActivity.consumeNotificationIntent]（手写字符串截取）、
 *   [MaodouchatNavGraph] 的 navDeepLink pattern、AndroidManifest 三处，规则互相漂移；
 * - 通知/Widget/二维码/邀请/网页链接各走一套跳转逻辑，无统一鉴权与参数校验门。
 *
 * 本文件只做「纯决策」：解析 + 清洗 + 目标建模 + 路由字符串生成。
 * 不触碰 NavController / Activity / Token，保持零行为变更，可被现有调用方渐进接入。
 * 业务执行前必须检查 [AppLinkDestination.requiresAuth] 并完成登录态校验（见 P08 Gate）。
 */
sealed interface AppLinkDestination {
    /** 是否需要已登录才能执行业务（当前全部业务目标均为 true）。 */
    val requiresAuth: Boolean

    /** 映射到现有 [Routes] 的导航路由字符串（与现有 builder 语义一致）。 */
    fun toRoute(): String

    data class ChatDetail(val chatId: String, val messageId: String? = null) : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String {
            val base = "chat_detail/${AppLinkRouter.encodePathSegment(chatId)}"
            return if (messageId.isNullOrBlank()) base
            else "$base?messageId=${AppLinkRouter.encodePathSegment(messageId)}"
        }
    }

    data class PublicProfile(val username: String) : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "public_profile/${AppLinkRouter.encodePathSegment(username)}"
    }

    data class PostDetail(val postId: String, val commentId: String? = null) : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String {
            val base = "post/${AppLinkRouter.encodePathSegment(postId)}"
            return "$base?comment=${AppLinkRouter.encodePathSegment(commentId.orEmpty())}"
        }
    }

    /** AI 任务屏（`Routes.aiTasks`）：会话级目标，独立于聊天详情路由。 */
    data class AiTasksChat(val chatId: String) : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "ai_tasks/${AppLinkRouter.encodePathSegment(chatId)}"
    }

    data class GroupInvite(val code: String) : AppLinkDestination {
        override val requiresAuth: Boolean = true
        // 暂无专用路由：先复用全局搜索/邀请落地页由调用方二次派发，路由字符串保留可解析形态。
        override fun toRoute(): String = "global_search"
    }

    data object NotificationCenter : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "notification_center"
    }

    data object CallHistory : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "call_history"
    }
}

sealed interface AppLinkParseResult {
    data class Accepted(val destination: AppLinkDestination) : AppLinkParseResult
    data class Rejected(val reason: String) : AppLinkParseResult
}

object AppLinkRouter {
    const val MAX_USERNAME_LENGTH = 64
    const val MAX_ID_LENGTH = 128
    const val MAX_INVITE_CODE_LENGTH = 64

    /**
     * 对外深链模式唯一事实源（公开资料页）。
     * - `NavGraph` 的 `navDeepLink` 列表直接由此生成；
     * - `AndroidManifest.xml` 的两个 intent-filter 与此逐项对应（XML 无法引用代码，
     *   改动时必须同步三处，见下注释）；
     * - `parseDeepLink` 只接受落在此白名单内的 scheme/host。
     */
    // Manifest 镜像：
    //   https + chat.mdou.me + pathPrefix /u/
    //   maodouchat + host u
    val publicProfileDeepLinkPatterns: List<String> = listOf(
        "https://chat.mdou.me/u/{username}",
        "https://chat.mdou.me/u/{username}?embed={embed}",
        "maodouchat://u/{username}",
    )

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
     */
    fun parseDeepLink(uriString: String): AppLinkParseResult {
        val raw = uriString.trim()
        if (raw.isEmpty()) return AppLinkParseResult.Rejected("empty")
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
     * 解析通知/Widget/系统入口 extras（key 与 NotificationIntents 对齐）。
     * 所有值均经过与深链相同的清洗器；非法值返回 null（调用方忽略，不导航）。
     */
    fun parseNotificationExtras(extras: Map<String, String?>): AppLinkDestination? {
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

    // ---- 清洗器（与 MainActivity 现有用户名规则对齐，其余 ID 取交集最严形态） ----

    fun sanitizeUsername(raw: String): String? {
        val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            .take(MAX_USERNAME_LENGTH)
        if (single.isBlank()) return null
        if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') }) return null
        return single
    }

    fun sanitizeChatId(raw: String): String? = sanitizeOpaqueId(raw)

    fun sanitizeMessageId(raw: String): String? = sanitizeOpaqueId(raw)

    fun sanitizePostId(raw: String): String? = sanitizeOpaqueId(raw)

    fun sanitizeInviteCode(raw: String): String? {
        val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            .take(MAX_INVITE_CODE_LENGTH)
        if (single.isBlank()) return null
        if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
        return single
    }

    private fun sanitizeOpaqueId(raw: String): String? {
        val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            .take(MAX_ID_LENGTH)
        if (single.isBlank()) return null
        // 拒绝路径分隔/空白/控制字符，其余透传（兼容现有 UUID/pairKey 形态）。
        if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
        return single
    }

    /**
     * 通知/Widget extras 用严格清洗：含路径分隔/查询符的值直接拒绝（而非静默截断），
     * 避免 "c1/evil" 被截成 "c1" 后误导航。合法 ID（UUID/pairKey/服务端雪花）不含这些字符。
     */
    fun sanitizeChatIdStrict(raw: String): String? =
        if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeChatId(raw)

    fun sanitizeMessageIdStrict(raw: String): String? =
        if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeMessageId(raw)

    fun sanitizePostIdStrict(raw: String): String? =
        if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizePostId(raw)

    /**
     * 来电唤醒入口（Telecom/FCM）用严格清洗：callId/senderId 均为服务端 opaque ID，
     * 含 /?# 直接拒绝。返回 null 时调用方按空串处理（仍走通用轮询，不定向响铃）。
     */
    fun sanitizeCallIdStrict(raw: String): String? =
        if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueId(raw)

    fun sanitizeUserIdStrict(raw: String): String? =
        if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueId(raw)

    // ---- 内部解析 ----

    private fun parseMaodouScheme(afterScheme: String): AppLinkParseResult {
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

    private fun parseHttpsChatMdou(pathAndQuery: String): AppLinkParseResult {
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

    private fun parseQueryParam(query: String, key: String): String? {
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

    /** RFC3986 unreserved 保持原样，其余 UTF-8 百分比编码（与 Uri.encode 默认行为对齐）。 */
    fun encodePathSegment(raw: String): String {
        val sb = StringBuilder()
        // 按字符而非字节判断 unreserved，避免多字节字符被误保留。
        for (ch in raw) {
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append('%')
                    sb.append(HEX[(b.toInt() shr 4) and 0xF])
                    sb.append(HEX[b.toInt() and 0xF])
                }
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun decodeComponent(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hi = HEX.indexOf(raw[i + 1].uppercaseChar())
                val lo = HEX.indexOf(raw[i + 2].uppercaseChar())
                if (hi >= 0 && lo >= 0) {
                    out.append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            out.append(if (c == '+') ' ' else c)
            i++
        }
        return out.toString()
    }
}
