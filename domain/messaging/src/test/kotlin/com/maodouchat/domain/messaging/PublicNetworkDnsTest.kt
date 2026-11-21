package com.maodouchat.domain.messaging

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicNetworkDnsTest {
    @Test
    fun ipv4CompatibleIpv6LiteralIsNotPublic() {
        with(PublicNetworkDns) {
            assertFalse(InetAddress.getByName("::10.0.0.1").isPublicNetworkAddress())
            assertFalse(InetAddress.getByName("::127.0.0.1").isPublicNetworkAddress())
        }
    }

    @Test
    fun lookupRejectsIpv4TransitionLiterals() {
        val dns = PublicNetworkDns.create()
        assertFailsWith<UnknownHostException> { dns.lookup("::10.0.0.1") }
        assertFailsWith<UnknownHostException> { dns.lookup("::ffff:10.0.0.1") }
    }

    @Test
    fun lookupAllowsPublicIpv6() {
        val dns = PublicNetworkDns.create()
        assertTrue(dns.lookup("2001:4860:4860::8888").isNotEmpty())
    }

    @Test
    fun privateIpv4AddressesAreNotPublic() {
        with(PublicNetworkDns) {
            assertFalse(InetAddress.getByName("10.0.0.5").isPublicNetworkAddress())
            assertFalse(InetAddress.getByName("172.16.0.1").isPublicNetworkAddress())
            assertFalse(InetAddress.getByName("192.168.1.1").isPublicNetworkAddress())
            assertFalse(InetAddress.getByName("169.254.1.1").isPublicNetworkAddress())
            assertFalse(InetAddress.getByName("127.0.0.1").isPublicNetworkAddress())
        }
    }

    @Test
    fun cgnatIsNotPublicButPublicIpv4Is() {
        with(PublicNetworkDns) {
            assertFalse(InetAddress.getByName("100.64.0.1").isPublicNetworkAddress())
            assertTrue(InetAddress.getByName("8.8.8.8").isPublicNetworkAddress())
        }
    }

    @Test
    fun ipv6UlaIsNotPublicButPublicIpv6Is() {
        with(PublicNetworkDns) {
            assertFalse(InetAddress.getByName("fd00::1").isPublicNetworkAddress())
            assertTrue(InetAddress.getByName("2001:4860:4860::8888").isPublicNetworkAddress())
        }
    }

    @Test
    fun lookupRejectsPrivateIpv4Literal() {
        val dns = PublicNetworkDns.create()
        assertFailsWith<UnknownHostException> { dns.lookup("10.0.0.1") }
    }

    @Test
    fun allowedPrivateHostBypassesCheck() {
        val dns = PublicNetworkDns.create(setOf("10.0.0.5"))
        assertEquals(listOf(InetAddress.getByName("10.0.0.5")), dns.lookup("10.0.0.5"))
    }
}
