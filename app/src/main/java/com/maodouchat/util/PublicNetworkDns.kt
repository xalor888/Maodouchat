package com.maodouchat.util

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

object PublicNetworkDns {
    private val IPV4_OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
    private val IPV4_TAIL = "(?:$IPV4_OCTET\\.){3}$IPV4_OCTET"
    private val IPV4_TRANSITION_LITERAL = Regex(
        "(?i)^(?:::ffff:|::|0:0:0:0:0:ffff:|0:0:0:0:0:0:)$IPV4_TAIL$"
    )

    fun create(allowedPrivateHosts: Set<String> = emptySet()): Dns {
        val allowed = allowedPrivateHosts.mapTo(hashSetOf()) { it.trim().trimEnd('.').lowercase() }
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val normalizedHost = hostname.trim().trimEnd('.').lowercase()
                if (normalizedHost !in allowed && IPV4_TRANSITION_LITERAL.matches(normalizedHost)) {
                    throw UnknownHostException("IPv4 transition literal is not a public network host")
                }
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || normalizedHost !in allowed && addresses.any { !it.isPublicNetworkAddress() }) {
                    throw UnknownHostException("Non-public network host")
                }
                return addresses
            }
        }
    }

    internal fun InetAddress.isPublicNetworkAddress(): Boolean {
        if (this is Inet6Address && this.isIPv4CompatibleAddress) return false
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return false
        }
        val bytes = address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
        } else if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false
        }
        return true
    }
}
