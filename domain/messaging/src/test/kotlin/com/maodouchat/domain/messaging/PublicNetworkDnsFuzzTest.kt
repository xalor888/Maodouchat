// Fuzz / random-oracle tests for PublicNetworkDns.isPublicNetworkAddress
// (checklist line 931, 152nd block). Test-only, zero production changes.
// The oracle is hand-written from the production source plus the JDK
// InetAddress predicates it relies on (any-local / loopback / link-local
// / site-local / multicast / IPv4-compatible), re-derived from the byte
// array so it never calls production or InetAddress helpers.
package com.maodouchat.domain.messaging

import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun isPublicNetworkAddressOracle152(bytes: ByteArray): Boolean {
    val raw = IntArray(bytes.size) { bytes[it].toInt() and 0xff }
    // InetAddress.getByAddress turns an IPv4-mapped IPv6 address
    // (::ffff:a.b.c.d) into an Inet4Address, so every predicate below
    // sees the embedded IPv4 bytes, not the 16-byte form.
    val b = if (raw.size == 16 && isIPv4MappedOracle152(raw)) raw.sliceArray(12..15) else raw
    if (b.size == 16 && isIPv4CompatibleOracle152(b)) return false
    if (isAnyLocalOracle152(b) || isLoopbackOracle152(b) || isLinkLocalOracle152(b) ||
        isSiteLocalOracle152(b) || isMulticastOracle152(b)
    ) {
        return false
    }
    if (b.size == 4) {
        val first = b[0]
        val second = b[1]
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false
        if (first == 100 && second in 64..127) return false
        if (first == 169 && second == 254) return false
        if (first == 172 && second in 16..31) return false
        if (first == 192 && second == 168) return false
    } else if (b.size == 16) {
        val first = b[0]
        if (first and 0xfe == 0xfc) return false
    }
    return true
}

internal fun isIPv4MappedOracle152(b: IntArray): Boolean {
    if (b.size != 16) return false
    for (i in 0 until 10) if (b[i] != 0) return false
    return b[10] == 0xff && b[11] == 0xff
}

internal fun isIPv4CompatibleOracle152(b: IntArray): Boolean {
    if (b.size != 16) return false
    for (i in 0 until 12) if (b[i] != 0) return false
    // :: and ::1 are any-local / loopback, not "compatible" addresses.
    if (b[12] == 0 && b[13] == 0 && b[14] == 0 && (b[15] == 0 || b[15] == 1)) return false
    return true
}

internal fun isAnyLocalOracle152(b: IntArray): Boolean = b.all { it == 0 }

internal fun isLoopbackOracle152(b: IntArray): Boolean {
    if (b.size == 4) return b[0] == 127
    if (b.size == 16) {
        for (i in 0 until 15) if (b[i] != 0) return false
        return b[15] == 1
    }
    return false
}

internal fun isLinkLocalOracle152(b: IntArray): Boolean {
    if (b.size == 4) return b[0] == 169 && b[1] == 254
    if (b.size == 16) return b[0] == 0xfe && (b[1] and 0xc0) == 0x80
    return false
}

internal fun isSiteLocalOracle152(b: IntArray): Boolean {
    if (b.size == 4) {
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
    }
    if (b.size == 16) return b[0] == 0xfe && (b[1] and 0xc0) == 0xc0
    return false
}

internal fun isMulticastOracle152(b: IntArray): Boolean {
    if (b.size == 4) return (b[0] and 0xf0) == 0xe0
    if (b.size == 16) return b[0] == 0xff
    return false
}

// ---------- generators ----------

internal val FIRST_BYTE_POOL152 = listOf(
    0, 1, 9, 10, 11, 63, 64, 99, 100, 101, 126, 127, 128, 168, 169, 171, 172,
    173, 191, 192, 193, 223, 224, 225, 239, 240, 254, 255,
)

internal val SECOND_BYTE_POOL152 = listOf(
    0, 1, 15, 16, 31, 32, 63, 64, 127, 128, 167, 168, 169, 253, 254, 255,
)

internal fun randomAddressBytes152(rnd: Random): ByteArray {
    val v6 = rnd.nextBoolean()
    val size = if (v6) 16 else 4
    val bytes = ByteArray(size)
    if (rnd.nextInt(10) < 8) {
        bytes[0] = FIRST_BYTE_POOL152[rnd.nextInt(FIRST_BYTE_POOL152.size)].toByte()
        bytes[1] = SECOND_BYTE_POOL152[rnd.nextInt(SECOND_BYTE_POOL152.size)].toByte()
        for (i in 2 until size) bytes[i] = rnd.nextInt(256).toByte()
        // Sometimes force a structured tail: all zero, ::1, mapped, compatible.
        when (rnd.nextInt(10)) {
            0 -> bytes.fill(0)
            1 -> {
                bytes.fill(0)
                bytes[size - 1] = 1
            }
            2 -> if (v6) {
                bytes.fill(0)
                bytes[10] = 0xff.toByte()
                bytes[11] = 0xff.toByte()
                bytes[12] = 8
                bytes[13] = 8
                bytes[14] = 8
                bytes[15] = 8
            }
            3 -> if (v6) {
                bytes.fill(0)
                bytes[12] = 8
                bytes[13] = 8
                bytes[14] = 8
                bytes[15] = 8
            }
        }
    } else {
        rnd.nextBytes(bytes)
    }
    return bytes
}

internal fun dnsWireJson152(bytes: ByteArray): String {
    val sb = StringBuilder()
    sb.append("{\"b\":[")
    for ((i, v) in bytes.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append(v.toInt() and 0xff)
    }
    sb.append("]}")
    return sb.toString()
}

internal fun address152(vararg octets: Int): ByteArray =
    ByteArray(octets.size) { octets[it].toByte() }

class PublicNetworkDnsFuzzTest {

    private fun check(bytes: ByteArray) {
        val addr = InetAddress.getByAddress(bytes)
        assertEquals(
            isPublicNetworkAddressOracle152(bytes),
            with(PublicNetworkDns) { addr.isPublicNetworkAddress() },
        )
    }

    @Test
    fun pinnedBoundaries() {
        // IPv4 range edges, each boundary both sides.
        val v4 = listOf(
            intArrayOf(0, 0, 0, 0), intArrayOf(0, 1, 2, 3),
            intArrayOf(9, 255, 255, 255), intArrayOf(10, 0, 0, 0),
            intArrayOf(10, 255, 255, 255), intArrayOf(11, 0, 0, 0),
            intArrayOf(100, 63, 255, 255), intArrayOf(100, 64, 0, 0),
            intArrayOf(100, 127, 255, 255), intArrayOf(100, 128, 0, 0),
            intArrayOf(126, 255, 255, 255), intArrayOf(127, 0, 0, 0),
            intArrayOf(127, 0, 0, 1), intArrayOf(127, 255, 255, 255),
            intArrayOf(128, 0, 0, 0), intArrayOf(169, 253, 255, 255),
            intArrayOf(169, 254, 0, 0), intArrayOf(169, 254, 255, 255),
            intArrayOf(169, 255, 0, 0), intArrayOf(172, 15, 255, 255),
            intArrayOf(172, 16, 0, 0), intArrayOf(172, 31, 255, 255),
            intArrayOf(172, 32, 0, 0), intArrayOf(192, 167, 255, 255),
            intArrayOf(192, 168, 0, 0), intArrayOf(192, 168, 255, 255),
            intArrayOf(192, 169, 0, 0), intArrayOf(223, 255, 255, 255),
            intArrayOf(224, 0, 0, 0), intArrayOf(239, 255, 255, 255),
            intArrayOf(240, 0, 0, 0), intArrayOf(255, 255, 255, 255),
            intArrayOf(8, 8, 8, 8), intArrayOf(1, 2, 3, 4),
        )
        for (octets in v4) check(address152(*octets))
        // IPv6: any-local, loopback, mapped, compatible, link/site local,
        // multicast, unique-local, global.
        val v6 = listOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2),
            intArrayOf(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xfe, 0xbf, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xfe, 0xc0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xfe, 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xff, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xfc, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xfd, 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0xfe, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        )
        for (octets in v6) check(address152(*octets))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(152002)
        repeat(2000) {
            check(randomAddressBytes152(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // IPv4 first two octets, exhaustively; last two fixed.
        for (first in 0..255) {
            for (second in 0..255) {
                check(address152(first, second, 1, 2))
            }
        }
        // IPv6 first byte exhaustively; second byte over the /10 edges.
        for (first in 0..255) {
            for (second in listOf(0, 0x3f, 0x40, 0x7f, 0x80, 0xbf, 0xc0, 0xff)) {
                val bytes = ByteArray(16)
                bytes[0] = first.toByte()
                bytes[1] = second.toByte()
                bytes[15] = 1
                check(bytes)
            }
        }
        // IPv6 compatible/mapped tails: first 12 bytes zero, last four swept.
        for (a in listOf(0, 1, 8, 127, 255)) {
            for (b in listOf(0, 1, 8, 255)) {
                val compat = ByteArray(16)
                compat[12] = a.toByte()
                compat[13] = b.toByte()
                compat[14] = 1
                compat[15] = 2
                check(compat)
                val mapped = ByteArray(16)
                mapped[10] = 0xff.toByte()
                mapped[11] = 0xff.toByte()
                mapped[12] = a.toByte()
                mapped[13] = b.toByte()
                mapped[14] = 1
                mapped[15] = 2
                check(mapped)
            }
        }
        // IPv6 mapped tails: every first-octet class behind ::ffff:.
        for (first in listOf(0, 1, 10, 100, 127, 169, 172, 192, 223, 224, 255)) {
            for (second in listOf(0, 1, 16, 64, 127, 128, 168, 254)) {
                for (tail in listOf(listOf(0, 1), listOf(1, 2))) {
                    val mapped = ByteArray(16)
                    mapped[10] = 0xff.toByte()
                    mapped[11] = 0xff.toByte()
                    mapped[12] = first.toByte()
                    mapped[13] = second.toByte()
                    mapped[14] = tail[0].toByte()
                    mapped[15] = tail[1].toByte()
                    check(mapped)
                }
            }
        }
    }
}
