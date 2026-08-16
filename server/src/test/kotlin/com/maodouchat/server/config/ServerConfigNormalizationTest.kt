package com.maodouchat.server.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigNormalizationTest {
    @Test
    fun `normalizes uppercase http schemes`() {
        assertEquals("https://chat.example.com:8443", normalizeHttpScheme("HTTPS://chat.example.com:8443/"))
        assertEquals("http://192.168.1.10:8080", normalizeHttpScheme("HTTP://192.168.1.10:8080/"))
    }

    @Test
    fun `normalizes surrounding whitespace and trailing slash`() {
        assertEquals("https://chat.example.com", normalizeHttpScheme("  https://chat.example.com//"))
    }
}
