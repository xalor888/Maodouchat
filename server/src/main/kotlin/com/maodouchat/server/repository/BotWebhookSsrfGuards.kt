package com.maodouchat.server.repository

// bot webhook 防护簇：SSRF 主机与 IP 字面量拦截（原 BotValidation 扩展按域拆出，零行为改动）。

import com.maodouchat.server.common.isAllowedWebhookAddress
import java.net.InetAddress

internal fun BotRepository.isBlockedSsrfHost(host: String): Boolean {
    val h = host.removeSurrounding("[", "]").trimEnd('.').lowercase()
    if (h in setOf("metadata.google.internal", "169.254.169.254")) return true
    if (h == "localhost" || h.endsWith(".localhost")) return true
    if (isBlockedIpv4Literal(h)) return true
    if (h.contains(":")) {
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.startsWith("fc") || h.startsWith("fd")) return true
        if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return true
        val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
        return !address.isAllowedWebhookAddress(allowLoopback = false)
    }
    return false
}


internal fun BotRepository.isBlockedIpv4Literal(host: String): Boolean {
    if (host.matches(ipv4HexLiteralPattern)) return true
    val parts = host.split(".")
    if (parts.size !in 1..4) return false
    if (parts.any { it.isBlank() }) return true
    if (parts.any { it.startsWith("0x") || (it.length > 1 && it.startsWith("0")) }) return true
    if (parts.size != 4) {
        return parts.all { it.matches(ipv4DigitsPattern) }
    }
    val octets = parts.map { it.toIntOrNull() }
    if (octets.any { it == null || it !in 0..255 }) return true
    val a = octets[0]!!
    val b = octets[1]!!
    val c = octets[2]!!
    val d = octets[3]!!
    return a == 0 || a == 10 || a == 127 || a >= 224 ||
        (a == 100 && b in 64..127) ||
        (a == 169 && b == 254) ||
        (a == 168 && b == 63 && c == 129 && d == 16) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 198 && b in 18..19) ||
        (a == 192 && b == 0 && c in setOf(0, 2)) ||
        (a == 192 && b == 31 && c == 196) ||
        (a == 192 && b == 52 && c == 193) ||
        (a == 192 && b == 88 && c == 99) ||
        (a == 192 && b == 175 && c == 48) ||
        (a == 198 && b == 51 && c == 100) ||
        (a == 203 && b == 0 && c == 113)
}


internal fun BotRepository.allowLoopbackWebhook(): Boolean =
    System.getenv("BOT_WEBHOOK_ALLOW_LOOPBACK")?.trim()?.equals("true", ignoreCase = true) == true


internal val ipv4HexLiteralPattern = Regex("""^0x[0-9a-f]+$""")


internal val ipv4DigitsPattern = Regex("""\d+""")
