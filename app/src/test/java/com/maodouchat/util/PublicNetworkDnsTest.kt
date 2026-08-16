package com.maodouchat.util

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertThrows(UnknownHostException::class.java) { dns.lookup("::10.0.0.1") }
        assertThrows(UnknownHostException::class.java) { dns.lookup("::ffff:10.0.0.1") }
    }

    @Test
    fun lookupAllowsPublicIpv6() {
        val dns = PublicNetworkDns.create()
        assertTrue(dns.lookup("2001:4860:4860::8888").isNotEmpty())
    }
}
