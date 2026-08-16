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
}
