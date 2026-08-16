package com.maodouchat.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiConfigTest {

    @Test
    fun wsUrlForNormalizesSchemeCase() {
        assertEquals("wss://chat.example.com/ws", ApiConfig.wsUrlFor("HTTPS://chat.example.com"))
        assertEquals("ws://192.168.1.10:8080/ws", ApiConfig.wsUrlFor("HTTP://192.168.1.10:8080"))
    }
}
