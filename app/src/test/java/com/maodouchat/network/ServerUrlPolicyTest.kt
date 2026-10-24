package com.maodouchat.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlPolicyTest {

    @Test
    fun validServerUrls_areAccepted() {
        assertNull(ServerUrlPolicy.validate("https://chat.example.com"))
        assertNull(ServerUrlPolicy.validate("https://chat.example.com/"))
        assertNull(ServerUrlPolicy.validate("http://192.168.1.10:8080"))
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:8443"))
        assertNull(ServerUrlPolicy.validate("HTTPS://chat.example.com"))
        assertNull(ServerUrlPolicy.validate("HTTP://192.168.1.10:8080"))
    }

    @Test
    fun invalidServerUrls_areMappedToExpectedProblems() {
        assertEquals(Problem.EMPTY, ServerUrlPolicy.validate(""))
        assertEquals(Problem.SCHEME, ServerUrlPolicy.validate("ftp://chat.example.com"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://user:pass@chat.example.com"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com?x=1"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com#frag"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com/api"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://chat.example.com:99999"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com:not-a-port"))
    }

    @Test
    fun rootPathOrNoPath_isAccepted() {
        assertFalse(ServerUrlPolicy.hasUnsupportedPath(null))
        assertFalse(ServerUrlPolicy.hasUnsupportedPath(""))
        assertFalse(ServerUrlPolicy.hasUnsupportedPath("/"))
    }

    @Test
    fun extraPath_isRejected() {
        assertTrue(ServerUrlPolicy.hasUnsupportedPath("/api"))
        assertTrue(ServerUrlPolicy.hasUnsupportedPath("chat"))
        assertTrue(ServerUrlPolicy.hasUnsupportedPath("//"))
    }

    @Test
    fun localOrPrivateHost_classification() {
        // loopback / local domains
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("localhost"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("LOCALHOST"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("127.0.0.1"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("::1"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("printer.local"))
        // RFC1918
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("10.0.0.1"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("172.16.0.1"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("172.31.255.255"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("192.168.1.10"))
        // public must NOT classify as local
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("8.8.8.8"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("1.1.1.1"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("chat.example.com"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("172.15.0.1"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("172.32.0.1"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("192.167.1.1"))
        // spoof shapes
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("10.0.0.1.evil.com"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("192.168.1.10.evil.com"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost(""))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("not-an-ip"))
    }

    @Test
    fun whitespacePaddedUrl_isTrimmedAndAccepted() {
        assertNull(ServerUrlPolicy.validate("  https://chat.example.com  "))
        assertNull(ServerUrlPolicy.validate("\thttps://chat.example.com/\n"))
    }

    @Test
    fun blankishInput_mapsToEmpty() {
        assertEquals(Problem.EMPTY, ServerUrlPolicy.validate("   "))
        assertEquals(Problem.EMPTY, ServerUrlPolicy.validate("\t\n "))
    }

    @Test
    fun portBoundaries() {
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:1"))
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:65535"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://chat.example.com:0"))
    }

    @Test
    fun ipv6LoopbackLiteral_isAccepted() {
        assertNull(ServerUrlPolicy.validate("https://[::1]"))
        assertNull(ServerUrlPolicy.validate("https://[::1]:8443"))
    }

    @Test
    fun trailingDotHost_isNormalized() {
        assertNull(ServerUrlPolicy.validate("https://chat.example.com."))
        assertNull(ServerUrlPolicy.validate("https://chat.example.com./"))
    }

    @Test
    fun uppercaseHost_isAccepted() {
        assertNull(ServerUrlPolicy.validate("https://CHAT.EXAMPLE.COM"))
        assertNull(ServerUrlPolicy.validate("Https://Chat.Example.COM:8443/"))
    }

    @Test
    fun ipv6NonLoopbackLiteral_isAccepted() {
        assertNull(ServerUrlPolicy.validate("https://[2001:db8::1]"))
        assertNull(ServerUrlPolicy.validate("https://[2001:db8::1]:8443"))
    }

    @Test
    fun hostLengthBoundary() {
        // 255 字符恰好放行，256 字符判无效。
        assertNull(ServerUrlPolicy.validate("https://" + "a".repeat(251) + ".com"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://" + "a".repeat(252) + ".com"))
    }

    @Test
    fun portJustAboveMaxAndLeadingZero() {
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://chat.example.com:65536"))
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:080"))
    }

    @Test
    fun schemeMissing_mapsToScheme() {
        assertEquals(Problem.SCHEME, ServerUrlPolicy.validate("//chat.example.com"))
        assertEquals(Problem.SCHEME, ServerUrlPolicy.validate("chat.example.com"))
    }

    @Test
    fun userinfoWithoutPassword_mapsToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://user@chat.example.com"))
    }

    @Test
    fun privateRangeBoundaries() {
        // 实现只认 127.0.0.1 本身，127.0.0.2 不算本地
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("127.0.0.2"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("0.0.0.0"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("10.255.255.255"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("172.16.0.0"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("192.168.0.0"))
    }

    @Test
    fun localSuffixIsCaseInsensitiveButNotSpoofable() {
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("Printer.LOCAL"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("example.local.evil.com"))
    }

    @Test
    fun whitespaceOnlyPath_isNotUnsupported() {
        assertFalse(ServerUrlPolicy.hasUnsupportedPath("   "))
        assertFalse(ServerUrlPolicy.hasUnsupportedPath("/ "))
        assertTrue(ServerUrlPolicy.hasUnsupportedPath(" /api "))
    }

    @Test
    fun queryOnRootPath_mapsToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com/?x=1"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com:8443/?x=1"))
    }

    @Test
    fun userinfoAndQueryCombined_mapsToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://user@chat.example.com?x=1"))
    }

    @Test
    fun uppercaseSchemeWithMaxPort_isAccepted() {
        assertNull(ServerUrlPolicy.validate("HTTPS://chat.example.com:65535"))
    }

    @Test
    fun malformedPrivateLikeIps_areNotLocal() {
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("10.0.0.256"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("999.0.0.1"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("10.0.0"))
    }

    @Test
    fun multiSegmentAndQueryLikePaths_areUnsupported() {
        assertTrue(ServerUrlPolicy.hasUnsupportedPath("/a/b"))
        assertTrue(ServerUrlPolicy.hasUnsupportedPath("/?"))
    }

    @Test
    fun portWithRootSlash_isAccepted() {
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:8443/"))
    }


    @Test
    fun portAndPathCombined_mapsToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com:8443/api"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com:8443/api/"))
    }

    @Test
    fun doubleSlashPath_mapsToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com//"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com//api"))
    }

    @Test
    fun trailingDotHostWithPath_mapsToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com./api"))
    }

    @Test
    fun maxPortWithRootSlash_isAccepted() {
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:65535/"))
    }

    @Test
    fun emptyQueryAndFragment_mapToExtra() {
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com?"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com#"))
    }


    @Test
    fun hostMissingWithPort_mapsToInvalid() {
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://:8443"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://user@"))
    }

    @Test
    fun leadingZeroIpv4Octets_parseAsDecimal() {
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("010.0.0.1"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("192.168.001.010"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("00.0.0.0"))
    }

    @Test
    fun localhostSpoofSuffixes_areNotLocal() {
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("localhost.evil.com"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("local"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("foo.localhost"))
    }

    @Test
    fun bracketedIpv6Literal_isNotLocalWhenCalledDirectly() {
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("[::1]"))
    }

    @Test
    fun localHttp_isAcceptedRegardlessOfBuild() {
        assertNull(ServerUrlPolicy.validate("http://localhost:8080"))
        assertNull(ServerUrlPolicy.validate("http://127.0.0.1:8080"))
        assertNull(ServerUrlPolicy.validate("http://10.0.0.5:8080"))
        assertNull(ServerUrlPolicy.validate("http://printer.local"))
    }

}
