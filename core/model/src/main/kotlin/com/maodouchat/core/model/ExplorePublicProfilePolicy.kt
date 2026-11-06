package com.maodouchat.core.model

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// 公开资料用户名归一化与加载失败文案映射。
object ExplorePublicProfilePolicy {

    fun normalizeUsername(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var value = raw.trim()
        value = decodeOnce(value).trim()
        value = value.trim('"', '\'')
        while (value.startsWith("@")) {
            value = value.drop(1).trim()
        }
        val slash = value.lastIndexOf('/')
        if (slash >= 0) {
            value = value.substring(slash + 1)
        }
        value = value.substringBefore('#').substringBefore('?').trim()
        return value
    }

    fun isValidUsername(username: String): Boolean = username.isNotBlank()

    fun displayLoadError(
        failure: Throwable,
        notFound: String,
        loadFailed: String,
        networkError: String
    ): String {
        val api = failure as? ApiException
        val status = api?.statusCode
        if (status == 404 || status == 410) return notFound
        val kind = api?.kind
        if (kind == ApiFailureKind.NETWORK || kind == ApiFailureKind.TIMEOUT) {
            return "$loadFailed: $networkError"
        }
        val server = api?.serverMessage?.trim().orEmpty()
            .ifBlank { failure.message?.trim().orEmpty() }
        return if (server.isNotBlank()) "$loadFailed: $server" else loadFailed
    }

    private fun decodeOnce(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
