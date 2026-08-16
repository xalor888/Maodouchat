package com.maodouchat.network

/**
 * 运行时服务器地址的纯校验策略。
 *
 * `ApiConfig.BASE_URL` 后续会直接拼接 `/api/...` 和 `/ws`，所以服务器地址只应包含
 * scheme、host 和可选 port；带额外路径会让客户端把请求打到错误前缀。
 */
internal object ServerUrlPolicy {

    fun hasUnsupportedPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.trim()
        return normalized != "/"
    }
}
