package com.maodouchat.navigation

sealed interface AppLinkDestination {
    /** 是否需要已登录才能执行业务（[ExternalUrl] 除外）。 */
    val requiresAuth: Boolean

    /** 映射到现有 [Routes] 的导航路由字符串（与现有 builder 语义一致）。 */
    fun toRoute(): String

    /**
     * 用户面 http(s) 外链：非 Compose 路由，由 [AppLinkOpener] 走系统浏览器。
     * 应用内深链仍优先经 [AppLinkRouter.parseDeepLink] 解析为其它目标。
     */
    data class ExternalUrl(val url: String) : AppLinkDestination {
        override val requiresAuth: Boolean = false
        override fun toRoute(): String =
            error("ExternalUrl is not a Compose route; open via AppLinkOpener")
    }

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
        override fun toRoute(): String =
            "join_group_invite/${AppLinkRouter.encodePathSegment(code)}"
    }

    data object NotificationCenter : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "notification_center"
    }

    data object CallHistory : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "call_history"
    }

    /** 通知中心 legacy 行：切到联系人 Tab（无独立 Compose 路由）。 */
    data object ContactsTab : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "contacts_tab"
    }

    /** 通知中心 legacy 行：打开未接来电收件箱（经 emitOpenMissedCalls）。 */
    data object MissedCallsTab : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "missed_calls_tab"
    }

    /** 通知中心 legacy 行：群邀请托盘清理目标（导航仍由调用方决定）。 */
    data object GroupInvitesTab : AppLinkDestination {
        override val requiresAuth: Boolean = true
        override fun toRoute(): String = "group_invites_tab"
    }
}
