package com.maodouchat.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiConfigTest {

    @Test
    fun wsUrlForNormalizesSchemeCase() {
        assertEquals("wss://chat.example.com/ws", ApiConfig.wsUrlFor("HTTPS://chat.example.com"))
        assertEquals("ws://192.168.1.10:8080/ws", ApiConfig.wsUrlFor("HTTP://192.168.1.10:8080"))
    }

    @Test
    fun wsUrlForTrimsTrailingSlash() {
        // 尾部斜杠先去掉再拼路径，避免出现 //ws
        assertEquals("wss://chat.example.com/ws", ApiConfig.wsUrlFor("https://chat.example.com/"))
    }

    @Test
    fun wsUrlForWithoutSchemeAppendsPathDirectly() {
        // 无 scheme 时不做任何推导，原样拼路径
        assertEquals("chat.example.com/ws", ApiConfig.wsUrlFor("chat.example.com"))
    }

    @Test
    fun wsUrlForTrimsSurroundingWhitespace() {
        assertEquals("ws://192.168.1.10:8080/ws", ApiConfig.wsUrlFor("  HTTP://192.168.1.10:8080  "))
    }

    @Test
    fun wsUrlForNonHttpSchemeFallsBackToPathAppend() {
        assertEquals("ftp://files.example.com/x/ws", ApiConfig.wsUrlFor("ftp://files.example.com/x"))
    }

    @Test
    fun wsUrlForKeepsPathOnHttpsBase() {
        assertEquals("wss://chat.example.com/app/ws", ApiConfig.wsUrlFor("https://chat.example.com/app"))
    }

    @Test
    fun wsUrlForStripsMultipleTrailingSlashes() {
        assertEquals("wss://chat.example.com/ws", ApiConfig.wsUrlFor("https://chat.example.com///"))
    }

    @Test
    fun wsUrlForPreservesHostCaseWhileNormalizingScheme() {
        assertEquals("wss://Chat.Example.COM/api/ws", ApiConfig.wsUrlFor("HTTPS://Chat.Example.COM/api"))
    }

    @Test
    fun wsUrlForEmptyInputAppendsPathOnly() {
        assertEquals("/ws", ApiConfig.wsUrlFor(""))
    }

    @Test
    fun wsUrlForKeepsPortOnHttpsBase() {
        assertEquals("wss://example.com:8443/app/ws", ApiConfig.wsUrlFor("https://example.com:8443/app/"))
    }

    @Test
    fun wsUrlForNonHttpOutputAppendsPathAgain() {
        // 非 http(s) scheme 走原样拼路径，对输出再调一次还是原样拼
        assertEquals("wss://chat.example.com/ws/ws", ApiConfig.wsUrlFor("wss://chat.example.com/ws"))
    }
}
