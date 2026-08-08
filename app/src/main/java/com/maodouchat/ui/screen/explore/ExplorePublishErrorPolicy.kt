package com.maodouchat.ui.screen.explore

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind

/**
 * Map explore publish/upload failures to UI kinds (no Android resources here).
 * Aligns with server rejectIfPostRestricted / rejectIfMessageRestricted copy.
 */
object ExplorePublishErrorPolicy {

    enum class Kind {
        POST_RESTRICTED,
        MESSAGE_RESTRICTED,
        SUSPENDED,
        AUTH,
        FALLBACK
    }

    fun classify(error: Throwable): Kind {
        val api = error as? ApiException
        val status = api?.statusCode
        val message = listOfNotNull(api?.serverMessage, error.message)
            .joinToString(" ")
            .lowercase()

        if (status == 401 || message.contains("请先登录") || message.contains("unauthorized") ||
            message.contains("sign in") || message.contains("token")
        ) {
            // Prefer auth only for clear auth failures; restriction copy can include 403.
            if (status == 401) return Kind.AUTH
        }
        if (isPostRestricted(message) || (status == 403 && message.contains("动态"))) {
            return Kind.POST_RESTRICTED
        }
        if (isMessageRestricted(message)) {
            return Kind.MESSAGE_RESTRICTED
        }
        if (isSuspended(message) || (status == 403 && message.contains("封禁"))) {
            return Kind.SUSPENDED
        }
        if (status == 401) return Kind.AUTH
        return Kind.FALLBACK
    }

    /**
     * Prefer server detail when present for restriction kinds (includes until timestamp);
     * fall back to localized string supplied by the caller.
     */
    fun displayMessage(error: Throwable, fallback: String): String {
        val kind = classify(error)
        val server = (error as? ApiException)?.serverMessage?.trim().orEmpty()
            .ifBlank { error.message?.trim().orEmpty() }
        return when (kind) {
            Kind.POST_RESTRICTED,
            Kind.MESSAGE_RESTRICTED,
            Kind.SUSPENDED -> server.ifBlank { fallback }
            Kind.AUTH -> server.ifBlank { fallback }
            Kind.FALLBACK -> server.ifBlank { fallback }
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
