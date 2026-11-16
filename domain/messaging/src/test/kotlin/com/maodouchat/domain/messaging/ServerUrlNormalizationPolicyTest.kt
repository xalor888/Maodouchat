package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerUrlNormalizationPolicyTest {

    @Test
    fun `https base maps to wss ws path`() {
        assertEquals(
            "wss://api.example.com/ws",
            ServerUrlNormalizationPolicy.wsUrlFor("https://api.example.com")
        )
    }

    @Test
    fun `http base maps to ws ws path`() {
        assertEquals(
            "ws://api.example.com/ws",
            ServerUrlNormalizationPolicy.wsUrlFor("http://api.example.com")
        )
    }

    @Test
    fun `no scheme appends ws path`() {
        assertEquals(
            "api.example.com/ws",
            ServerUrlNormalizationPolicy.wsUrlFor("api.example.com")
        )
    }

    @Test
    fun `ws url trims trailing slash`() {
        assertEquals(
            "wss://api.example.com/ws",
            ServerUrlNormalizationPolicy.wsUrlFor("https://api.example.com/")
        )
    }

    @Test
    fun `normalize lowercases scheme`() {
        assertEquals(
            "https://api.example.com",
            ServerUrlNormalizationPolicy.normalizeBaseUrl("HTTPS://api.example.com/")
        )
    }

    @Test
    fun `normalize without scheme only trims`() {
        assertEquals(
            "api.example.com",
            ServerUrlNormalizationPolicy.normalizeBaseUrl("  api.example.com/  ")
        )
    }
}
