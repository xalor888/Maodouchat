package com.maodouchat.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlPolicyTest {

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
