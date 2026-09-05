package com.maodouchat.settings.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSwitchTransactionTest {

    @Test
    fun `validateServerUrl rejects empty and whitespace URLs`() {
        assertNotNull(ServerSwitchTransaction.validateServerUrl(""))
        assertNotNull(ServerSwitchTransaction.validateServerUrl("   "))
    }

    @Test
    fun `validateServerUrl rejects non-http protocols`() {
        val ftpResult = ServerSwitchTransaction.validateServerUrl("ftp://chat.example.com")
        assertNotNull(ftpResult)
        assertEquals("Only http:// or https:// addresses are supported", ftpResult)

        val wsResult = ServerSwitchTransaction.validateServerUrl("ws://chat.example.com")
        assertNotNull(wsResult)
        assertEquals("Only http:// or https:// addresses are supported", wsResult)
    }

    @Test
    fun `validateServerUrl accepts valid http and https URLs`() {
        assertNull(ServerSwitchTransaction.validateServerUrl("https://api.example.com"))
        assertNull(ServerSwitchTransaction.validateServerUrl("http://192.168.1.100:8080"))
        assertNull(ServerSwitchTransaction.validateServerUrl("http://localhost:3000"))
    }

    @Test
    fun `normalizeUrl trims whitespace and trailing slashes`() {
        assertEquals("https://api.example.com", ServerSwitchTransaction.normalizeUrl("  https://api.example.com/  "))
        assertEquals("http://10.0.0.2:8000", ServerSwitchTransaction.normalizeUrl("http://10.0.0.2:8000/"))
    }
}
