package com.maodouchat.network

import com.maodouchat.BuildConfig
import java.net.URI
import java.net.URISyntaxException

/**
 * 运行时服务器地址的纯校验策略。
 *
 * `ApiConfig.BASE_URL` 后续会直接拼接 `/api/...` 和 `/ws`，所以服务器地址只应包含
 * scheme、host 和可选 port；带额外路径会让客户端把请求打到错误前缀。
 */
internal object ServerUrlPolicy {

    enum class Problem {
        EMPTY,
        INVALID,
        SCHEME,
        HOST,
        EXTRA,
        PORT,
    }

    fun validate(value: String): Problem? {
        val raw = value.trim()
        if (raw.isBlank()) return Problem.EMPTY

        val uri = try {
            URI(raw)
        } catch (error: URISyntaxException) {
            return when {
                error.reason?.contains("port", ignoreCase = true) == true -> Problem.PORT
                error.reason?.contains("authority", ignoreCase = true) == true ||
                    error.reason?.contains("host", ignoreCase = true) == true ||
                    raw.endsWith(":") -> Problem.HOST
                else -> Problem.INVALID
            }
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Problem.SCHEME

        val host = uri.host
            ?.trim()
            ?.removeSurrounding("[", "]")
            ?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() }
            ?: return if (uri.rawAuthority?.contains(":") == true) Problem.INVALID else Problem.HOST
        if (host.length > 255) return Problem.INVALID

        // 发布版禁止公网明文 HTTP：局域网内明文 HTTP/WS (私有IP/localhost/local域名) 允许以支持自托管与内网部署。
        if (scheme == "http" && !BuildConfig.DEBUG && !isLocalOrPrivateHost(host)) return Problem.SCHEME

        if (uri.userInfo != null || uri.query != null || uri.fragment != null || hasUnsupportedPath(uri.rawPath)) {
            return Problem.EXTRA
        }

        val port = uri.port
        if (port != -1 && port !in 1..65_535) return Problem.PORT
        return null
    }

    fun isLocalOrPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local", ignoreCase = true)) return true
        if (host == "127.0.0.1" || host == "::1") return true
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val p0 = parts[0].toInt()
            val p1 = parts[1].toInt()
            if (p0 == 10) return true
            if (p0 == 172 && p1 in 16..31) return true
            if (p0 == 192 && p1 == 168) return true
        }
        return false
    }

    fun hasUnsupportedPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.trim()
        return normalized != "/"
    }
}
