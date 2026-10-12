package com.maodouchat.server.repository

// bot webhook 安全簇（token 哈希/URL 白名单/SSRF 纵深校验）：原 BotRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BotApps
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.InetAddress

/** Returns the stored token hash for a bot, or null if not found. Used for webhook signing. */
fun BotRepository.getTokenHash(botId: String): String? = transaction {
    BotApps.selectAll().where { BotApps.id eq botId }
        .firstOrNull()?.get(BotApps.tokenHash)
}

internal fun BotRepository.isAllowedWebhookUrl(url: String): Boolean {
    return try {
        val u = java.net.URI(url)
        val scheme = (u.scheme ?: "").lowercase()
        val host = (u.host ?: "").lowercase()
        if (host.isBlank() || u.userInfo != null || u.fragment != null) return false
        when (scheme) {
            "https" -> !isBlockedSsrfHost(host)
            "http" -> allowLoopbackWebhook() && host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

/** Revalidate immediately before every outbound request. DNS failures are blocked. */
internal fun BotRepository.isWebhookDeliveryAllowed(url: String): Boolean {
    if (!isAllowedWebhookUrl(url)) return false
    return try {
        val uri = java.net.URI(url)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        when {
            scheme == "http" -> allowLoopbackWebhook() && host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            scheme == "https" -> host.isNotBlank() && !resolvesToBlockedIp(host)
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * DNS 解析后校验每个 IP 是否落入 SSRF 封禁段。
 * 仅在发送时（已处于 Dispatchers.IO）调用，作为主机名字符串校验之外的纵深防御，
 * 防止域名注册后通过 DNS rebinding 解析到内网/元数据地址。
 * 解析失败按 fail-closed 处理（视为封禁），避免向无法解析的主机发起请求。
 */
internal fun BotRepository.resolvesToBlockedIp(host: String): Boolean {
    return try {
        java.net.InetAddress.getAllByName(host).any { addr ->
            addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress ||
                addr.isMulticastAddress ||
                isBlockedSsrfHost(addr.hostAddress ?: "")
        }
    } catch (_: Exception) {
        true
    }
}
