package com.maodouchat.core.model

// 探索发布失败按服务端限制文案分类到 UI kind。
object ExplorePublishErrorPolicy {

    fun classify(error: Throwable): ExplorePublishErrorKind {
        val api = error as? ApiException
        val status = api?.statusCode
        val message = listOfNotNull(api?.serverMessage, error.message)
            .joinToString(" ")
            .lowercase()

        if (status == 401 || message.contains("请先登录") || message.contains("unauthorized") ||
            message.contains("sign in") || message.contains("token")
        ) {
            // Prefer auth only for clear auth failures; restriction copy can include 403.
            if (status == 401) return ExplorePublishErrorKind.AUTH
        }
        if (isPostRestricted(message) || (status == 403 && message.contains("动态"))) {
            return ExplorePublishErrorKind.POST_RESTRICTED
        }
        if (isMessageRestricted(message)) {
            return ExplorePublishErrorKind.MESSAGE_RESTRICTED
        }
        if (isSuspended(message) || (status == 403 && message.contains("封禁"))) {
            return ExplorePublishErrorKind.SUSPENDED
        }
        if (status == 401) return ExplorePublishErrorKind.AUTH
        return ExplorePublishErrorKind.FALLBACK
    }

    // 限制类优先取服务端详情（含解封时间），否则用调用方文案。
    fun displayMessage(error: Throwable, fallback: String): String {
        val kind = classify(error)
        val server = (error as? ApiException)?.serverMessage?.trim().orEmpty()
            .ifBlank { error.message?.trim().orEmpty() }
        return when (kind) {
            ExplorePublishErrorKind.POST_RESTRICTED,
            ExplorePublishErrorKind.MESSAGE_RESTRICTED,
            ExplorePublishErrorKind.SUSPENDED -> server.ifBlank { fallback }
            ExplorePublishErrorKind.AUTH -> server.ifBlank { fallback }
            ExplorePublishErrorKind.FALLBACK -> server.ifBlank { fallback }
        }
    }

    private fun isPostRestricted(message: String): Boolean =
        message.contains("限制发布动态") ||
            message.contains("限制发动态") ||
            message.contains("禁动态") ||
            message.contains("post restricted") ||
            message.contains("restricted from posting")

    private fun isMessageRestricted(message: String): Boolean =
        message.contains("限制发消息") ||
            message.contains("禁言") ||
            message.contains("message restricted")

    private fun isSuspended(message: String): Boolean =
        message.contains("临时封禁") ||
            message.contains("账号已被") ||
            message.contains("suspended") ||
            message.contains("banned")
}
