package com.maodouchat.slim

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 贴纸资源 URL 同源策略。
 *
 * 远程清单由自有服务器下发，理论上可信；但清单中若允许任意绝对 URL，客户端会把
 * OkHttp 请求导向外域甚至内网地址。这里把所有贴纸地址约束到当前 API 服务器同源，
 * 相对路径会解析为同源绝对地址，跨域/跨端口/跨 scheme 的地址直接丢弃。
 */
internal object StickerSourcePolicy {

    private val allowedSchemes = setOf("http", "https")

    fun resolve(rawUrl: String, baseUrl: String): String? {
        val raw = rawUrl.trim()
        if (raw.isBlank()) return null

        val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (base.scheme !in allowedSchemes || base.host.isBlank()) return null

        val resolved = base.resolve(raw) ?: return null
        if (resolved.scheme !in allowedSchemes || resolved.host.isBlank()) return null
        if (resolved.scheme != base.scheme ||
            !resolved.host.equals(base.host, ignoreCase = true) ||
            resolved.port != base.port
        ) {
            return null
        }

        // 拒绝凭据和 fragment：贴纸下载不需要，且可避免 URL 解析歧义。
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
        if (resolved.fragment != null) return null
        return resolved.toString()
    }
}
