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
        assertEquals(ServerUrlPolicy.Problem.EMPTY, ServerUrlPolicy.validate(""))
        assertEquals(ServerUrlPolicy.Problem.SCHEME, ServerUrlPolicy.validate("ftp://chat.example.com"))
        assertEquals(ServerUrlPolicy.Problem.HOST, ServerUrlPolicy.validate("https://"))
        assertEquals(ServerUrlPolicy.Problem.EXTRA, ServerUrlPolicy.validate("https://user:pass@chat.example.com"))
        assertEquals(ServerUrlPolicy.Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com?x=1"))
        assertEquals(ServerUrlPolicy.Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com#frag"))
        assertEquals(ServerUrlPolicy.Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com/api"))
        assertEquals(ServerUrlPolicy.Problem.PORT, ServerUrlPolicy.validate("https://chat.example.com:99999"))
        assertEquals(ServerUrlPolicy.Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com:not-a-port"))
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
}
