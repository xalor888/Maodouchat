package com.maodouchat.server.common

import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// isAllowedWebhookAddress restated by hand: a loopback address is
// only allowed when the caller opted in; any-local, loopback,
// link-local, site-local and multicast addresses are always refused;
// IPv4 then applies the reserved-range table, IPv6 the global
// unicast range plus the 2001::/23, 2001:db8::/32, 2002::/16 and
// 3fff::/20 carve-outs. A 16-byte input in IPv4-mapped form (ten zero
// bytes + ff ff) is treated as the IPv4 address in its last four
// bytes, exactly like InetAddress.getByAddress does; the compatible
// form (::a.b.c.d) stays IPv6.
internal fun isAllowedWebhookAddressOracle177(octets: List<Int>, allowLoopback: Boolean): Boolean {
    val v4 = when {
        octets.size == 4 -> octets
        octets.size == 16 && octets.subList(0, 10).all { it == 0 } &&
            octets[10] == 0xff && octets[11] == 0xff -> octets.subList(12, 16)
        else -> null
    }
    if (v4 != null) {
        val first = v4[0]
        val second = v4[1]
        val isLoopback = first == 127
        if (allowLoopback) return isLoopback
        if (v4.all { it == 0 } || isLoopback) return false
        if (first == 169 && second == 254) return false
        if (first == 10 || (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
        ) {
            return false
        }
        if (first in 224..239) return false
        return when {
            first == 0 || first == 10 || first == 127 || first >= 224 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 168 && second == 63 && v4[2] == 129 && v4[3] == 16 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 192 && second == 0 && v4[2] in setOf(0, 2) -> false
            first == 192 && second == 31 && v4[2] == 196 -> false
            first == 192 && second == 52 && v4[2] == 193 -> false
            first == 192 && second == 88 && v4[2] == 99 -> false
            first == 192 && second == 175 && v4[2] == 48 -> false
            first == 198 && second == 51 && v4[2] == 100 -> false
            first == 203 && second == 0 && v4[2] == 113 -> false
            else -> true
        }
    }
    val o = octets
    val isLoopback = o.subList(0, 15).all { it == 0 } && o[15] == 1
    if (allowLoopback) return isLoopback
    if (o.all { it == 0 } || isLoopback) return false
    if (o[0] == 0xfe && (o[1] and 0xc0) == 0x80) return false
    if (o[0] == 0xfe && (o[1] and 0xc0) == 0xc0) return false
    if (o[0] == 0xff) return false
    if (o[0] !in 0x20..0x3f) return false
    return when {
        o[0] == 0x20 && o[1] == 0x01 && o[2] <= 0x01 -> false
        o[0] == 0x20 && o[1] == 0x01 && o[2] == 0x0d && o[3] == 0xb8 -> false
        o[0] == 0x20 && o[1] == 0x02 -> false
        o[0] == 0x3f && o[1] == 0xff && (o[2] and 0xf0) == 0 -> false
        else -> true
    }
}

// Production isAllowedWebhookAddress gates outbound webhook targets.
internal fun isAllowedWebhookAddressProduction177(octets: List<Int>, allowLoopback: Boolean): Boolean {
    val bytes = ByteArray(octets.size) { octets[it].toByte() }
    return InetAddress.getByAddress(bytes).isAllowedWebhookAddress(allowLoopback)
}

// ---------- generators ----------

internal data class WebhookAddressCase177(
    val octets: List<Int>,
    val allowLoopback: Boolean,
)

internal val WEBHOOK_V4_FIRST177: List<Int> = listOf(
    0, 1, 8, 10, 100, 127, 168, 169, 172, 192, 198, 203, 223, 224, 239, 240, 255,
)

internal val WEBHOOK_V4_SECOND177: List<Int> = listOf(
    0, 2, 16, 18, 19, 31, 32, 51, 52, 63, 64, 88, 99, 100, 113, 127, 129, 168, 175, 193, 196, 254, 255,
)

internal val WEBHOOK_V4_REST177: List<Int> = listOf(0, 1, 2, 16, 48, 99, 100, 113, 129, 193, 196, 255)

internal val WEBHOOK_V6_FIRST177: List<Int> = listOf(
    0x00, 0x10, 0x1f, 0x20, 0x21, 0x26, 0x3e, 0x3f, 0x40, 0xfe, 0xff,
)

internal val WEBHOOK_V6_SECOND177: List<Int> = listOf(
    0x00, 0x01, 0x02, 0x0d, 0x80, 0xbf, 0xc0, 0xff,
)

internal val WEBHOOK_V6_THIRD177: List<Int> = listOf(0x00, 0x01, 0x02, 0x0d, 0x0f, 0x10, 0xb8)

internal fun randomWebhookAddressCase177(rnd: Random): WebhookAddressCase177 {
    val allowLoopback = rnd.nextBoolean()
    val kind = rnd.nextInt(10)
    val octets: List<Int> = when {
        kind < 4 -> List(4) { rnd.nextInt(256) }
        kind < 6 -> listOf(
            WEBHOOK_V4_FIRST177[rnd.nextInt(WEBHOOK_V4_FIRST177.size)],
            WEBHOOK_V4_SECOND177[rnd.nextInt(WEBHOOK_V4_SECOND177.size)],
            WEBHOOK_V4_REST177[rnd.nextInt(WEBHOOK_V4_REST177.size)],
            WEBHOOK_V4_REST177[rnd.nextInt(WEBHOOK_V4_REST177.size)],
        )
        kind < 8 -> List(16) { rnd.nextInt(256) }
        kind == 8 -> listOf(
            WEBHOOK_V6_FIRST177[rnd.nextInt(WEBHOOK_V6_FIRST177.size)],
            WEBHOOK_V6_SECOND177[rnd.nextInt(WEBHOOK_V6_SECOND177.size)],
            WEBHOOK_V6_THIRD177[rnd.nextInt(WEBHOOK_V6_THIRD177.size)],
            rnd.nextInt(256),
        ) + List(12) { rnd.nextInt(256) }
        else -> listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff) + List(4) { rnd.nextInt(256) }
    }
    return WebhookAddressCase177(octets, allowLoopback)
}

internal fun escJsonWebhook177(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun webhookAddressWireJson177(c: WebhookAddressCase177): String =
    "{\"octets\":[${c.octets.joinToString(",")}],\"allowLoopback\":${c.allowLoopback}}"

internal fun webhookAddressProjectionJson177(allowed: Boolean): String =
    if (allowed) "true" else "false"

class WebhookAddressPolicyFuzzTest {

    private fun checkWebhookAddress177(c: WebhookAddressCase177) {
        assertEquals(
            webhookAddressProjectionJson177(
                isAllowedWebhookAddressOracle177(c.octets, c.allowLoopback)
            ),
            webhookAddressProjectionJson177(
                isAllowedWebhookAddressProduction177(c.octets, c.allowLoopback)
            ),
            "webhookAddress($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // IPv4 first-octet sweep against the interesting second octets,
        // plus the pinned reserved-range table rows.
        for (first in 0..255) {
            for (second in listOf(0, 64, 254)) {
                for (allow in listOf(false, true)) {
                    checkWebhookAddress177(
                        WebhookAddressCase177(listOf(first, second, 0, 1), allow)
                    )
                }
            }
        }
        val pinnedV4 = listOf(
            listOf(0, 0, 0, 0), listOf(0, 1, 2, 3), listOf(10, 0, 0, 1),
            listOf(10, 255, 255, 255), listOf(127, 0, 0, 1), listOf(127, 255, 255, 254),
            listOf(224, 0, 0, 1), listOf(239, 255, 255, 255), listOf(240, 0, 0, 1),
            listOf(255, 255, 255, 255), listOf(172, 16, 0, 1), listOf(172, 31, 255, 255),
            listOf(172, 15, 0, 1), listOf(172, 32, 0, 1), listOf(192, 168, 1, 1),
            listOf(198, 18, 0, 1), listOf(198, 19, 255, 255), listOf(198, 17, 0, 1),
            listOf(198, 20, 0, 1), listOf(169, 254, 1, 1), listOf(192, 0, 0, 1),
            listOf(192, 0, 2, 1), listOf(192, 0, 1, 1), listOf(192, 31, 196, 1),
            listOf(192, 52, 193, 1), listOf(192, 88, 99, 1), listOf(192, 175, 48, 1),
            listOf(198, 51, 100, 1), listOf(203, 0, 113, 1), listOf(100, 64, 0, 1),
            listOf(100, 127, 0, 1), listOf(100, 63, 0, 1), listOf(100, 128, 0, 1),
            listOf(168, 63, 129, 16), listOf(168, 63, 129, 17), listOf(8, 8, 8, 8),
            listOf(1, 1, 1, 1),
        )
        for (octets in pinnedV4) {
            for (allow in listOf(false, true)) {
                checkWebhookAddress177(WebhookAddressCase177(octets, allow))
            }
        }
        // IPv6 first/second/third-octet pool cross product, plus the
        // mapped-form and special addresses.
        for (first in WEBHOOK_V6_FIRST177) {
            for (second in WEBHOOK_V6_SECOND177) {
                for (third in WEBHOOK_V6_THIRD177) {
                    val octets = listOf(first, second, third, 0) + List(12) { 0 }
                    for (allow in listOf(false, true)) {
                        checkWebhookAddress177(WebhookAddressCase177(octets, allow))
                    }
                }
            }
        }
        val pinnedV6 = listOf(
            List(16) { 0 },
            List(15) { 0 } + 1,
            listOf(0xfe, 0x80) + List(14) { 0 },
            listOf(0xfe, 0xbf) + List(14) { 0 },
            listOf(0xfe, 0xc0) + List(14) { 0 },
            listOf(0xfe, 0xff) + List(14) { 0 },
            listOf(0xff, 0x02) + List(14) { 0 },
            listOf(0x20, 0x01, 0x0d, 0xb8) + List(12) { 0 },
            listOf(0x20, 0x01, 0x00, 0x00) + List(12) { 0 },
            listOf(0x20, 0x01, 0x01, 0x00) + List(12) { 0 },
            listOf(0x20, 0x01, 0x02, 0x00) + List(12) { 0 },
            listOf(0x20, 0x02) + List(14) { 0 },
            listOf(0x3f, 0xff, 0x00, 0x00) + List(12) { 0 },
            listOf(0x3f, 0xff, 0x0f, 0x00) + List(12) { 0 },
            listOf(0x3f, 0xff, 0x10, 0x00) + List(12) { 0 },
            listOf(0x26, 0x06, 0x47, 0x00) + List(12) { 0 },
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1),
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8),
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 10, 0, 0, 1),
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1),
        )
        for (octets in pinnedV6) {
            for (allow in listOf(false, true)) {
                checkWebhookAddress177(WebhookAddressCase177(octets, allow))
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(177001)
        repeat(4000) { checkWebhookAddress177(randomWebhookAddressCase177(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every second octet for the first octets with range rules,
        // and every second octet for the IPv6 global-unicast prefixes.
        for (first in listOf(100, 168, 172, 192, 198, 203)) {
            for (second in 0..255) {
                checkWebhookAddress177(
                    WebhookAddressCase177(listOf(first, second, 0, 0), false)
                )
            }
        }
        for (first in listOf(0x20, 0x3f)) {
            for (second in 0..255) {
                checkWebhookAddress177(
                    WebhookAddressCase177(listOf(first, second, 0, 0) + List(12) { 0 }, false)
                )
            }
        }
        for (third in 0..255) {
            checkWebhookAddress177(
                WebhookAddressCase177(listOf(0x20, 0x01, third, 0) + List(12) { 0 }, false)
            )
            checkWebhookAddress177(
                WebhookAddressCase177(listOf(0x3f, 0xff, third, 0) + List(12) { 0 }, false)
            )
        }
    }
}
