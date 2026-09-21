package com.maodouchat.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G178：`WebRTCIcePolicy` 的测试（G178 刚从 WebRTCManager 的 3 处同构写法抽出）。
 */
class WebRTCIcePolicyTest {

    @Test
    fun `empty configuration falls back to public stun`() {
        val resolved = resolveIceServers(emptyList())
        assertEquals(CallIceServer.defaultStun(), resolved)
        assertTrue("回落结果应含 google stun", resolved.single().urls.any { it.contains("stun.l.google.com") })
    }

    @Test
    fun `non empty configuration is returned untouched`() {
        val configured = listOf(
            CallIceServer(listOf("turn:turn.example.com:3478"), "user", "pass")
        )
        assertEquals(configured, resolveIceServers(configured))
        // 原样返回且是同一实例——不复制，避免引用被悄悄换掉
        assertSame(configured, resolveIceServers(configured))
    }

    @Test
    fun `the fallback is stun only`() {
        assertTrue(CallIceServer.isStunOnly(resolveIceServers(emptyList())))
    }

    @Test
    fun `a turn configuration is preserved verbatim`() {
        val configured = listOf(
            CallIceServer(listOf("stun:stun.example.com"), "", ""),
            CallIceServer(listOf("turns:turn.example.com:5349"), "u", "c"),
        )
        val resolved = resolveIceServers(configured)
        assertEquals(2, resolved.size)
        assertTrue(CallIceServer.isStunOnly(resolved).not())
        // 凭据不被抹掉
        assertEquals("u", resolved[1].username)
        assertEquals("c", resolved[1].credential)
    }
}
