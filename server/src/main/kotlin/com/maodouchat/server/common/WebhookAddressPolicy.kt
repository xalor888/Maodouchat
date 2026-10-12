package com.maodouchat.server.common

// 地址白名单校验簇：原 WebhookSecurityUtils 顶层声明按域拆出的簇（同包调用，零行为改动）。
import java.net.Inet4Address
import java.net.InetAddress

internal fun InetAddress.isAllowedWebhookAddress(allowLoopback: Boolean): Boolean {
    if (allowLoopback) return isLoopbackAddress
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    if (this !is Inet4Address) {
        val octets = address.map { it.toInt() and 0xff }
        if (octets.size != 16 || octets[0] !in 0x20..0x3f) return false
        return when {
            // IANA special-purpose / transition ranges must not tunnel to a blocked IPv4 target.
            octets[0] == 0x20 && octets[1] == 0x01 && octets[2] <= 0x01 -> false // 2001::/23
            octets[0] == 0x20 && octets[1] == 0x01 && octets[2] == 0x0d && octets[3] == 0xb8 -> false // 2001:db8::/32
            octets[0] == 0x20 && octets[1] == 0x02 -> false // 2002::/16 (6to4)
            octets[0] == 0x3f && octets[1] == 0xff && (octets[2] and 0xf0) == 0 -> false // 3fff::/20
            else -> true
        }
    }
    val octets = address.map { it.toInt() and 0xff }
    val first = octets[0]
    val second = octets[1]
    return when {
        first == 0 || first == 10 || first == 127 || first >= 224 -> false
        first == 100 && second in 64..127 -> false
        first == 169 && second == 254 -> false
        first == 168 && second == 63 && octets[2] == 129 && octets[3] == 16 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 168 -> false
        first == 198 && second in 18..19 -> false
        first == 192 && second == 0 && octets[2] in setOf(0, 2) -> false
        first == 192 && second == 31 && octets[2] == 196 -> false
        first == 192 && second == 52 && octets[2] == 193 -> false
        first == 192 && second == 88 && octets[2] == 99 -> false
        first == 192 && second == 175 && octets[2] == 48 -> false
        first == 198 && second == 51 && octets[2] == 100 -> false
        first == 203 && second == 0 && octets[2] == 113 -> false
        else -> true
    }
}
